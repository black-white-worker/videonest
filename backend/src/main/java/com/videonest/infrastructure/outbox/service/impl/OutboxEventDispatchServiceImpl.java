package com.videonest.infrastructure.outbox.service.impl;

import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import com.videonest.infrastructure.outbox.mapper.OutboxEventMapper;
import com.videonest.infrastructure.outbox.service.OutboxEventDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Outbox消息投递接口具体实现类
 * 完整执行：恢复卡死中的消息 → 批量拉取待发送消息 → 抢占锁防止并发重复发送 → 单条发送MQ → 监听Publisher Confirm结果 → 更新成功/失败重试状态
 * 数据库乐观锁防并发、指数退避重试、RabbitMQ Confirm同步等待、批量拉取减少DBIO
 */
@Service
@Slf4j
public class OutboxEventDispatchServiceImpl implements OutboxEventDispatchService {

    // 单次批量查询多少条待发送消息
    private static final int DISPATCH_BATCH_SIZE = 50;
    // 等待RabbitMQ Confirm确认的超时时间
    private static final int CONFIRM_TIMEOUT_SECONDS = 5;
    // 最大重试延迟上限300秒
    private static final int MAX_RETRY_DELAY_SECONDS = 300;
    // 最大位移位数，控制2的n次方最大到2^8
    private static final int MAX_RETRY_SHIFT = 8;
    // 数据库存储异常信息的最大长度，防止超长报错文本撑爆字段
    private static final int MAX_ERROR_LENGTH = 500;

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxEventDispatchServiceImpl(
            OutboxEventMapper outboxEventMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 定时任务
     */
    @Override
    public void dispatchReadyEvents() {
        // 恢复长时间卡在PROCESSING处理中的脏数据
        // 场景：上一轮执行中途服务宕机，消息状态被标记为处理中但实际未发送，重置为待发送，允许下一轮重试
        outboxEventMapper.recoverStaleProcessing();
        // 批量查询最多50条满足发送条件的就绪消息（到了重试时间、未发送、非死信）
        /*
         * Outbox 是定时批量轮询消费的场景，并发争抢同一条消息的冲突概率很低，乐观锁无阻塞、无锁开销
        * */
        for (OutboxEvent event : outboxEventMapper.selectReady(DISPATCH_BATCH_SIZE)) {
            // 返回受影响行数=0 代表已经被其他实例抢占，跳过本条，解决集群多实例重复发送问题
            if (outboxEventMapper.claim(event.getId()) == 0) {
                continue;
            }
            // 抢占成功，执行单条消息投递逻辑
            dispatchOne(event);
        }
    }

    /**
     * 单条消息发送至RabbitMQ + 同步等待Publisher Confirm确认
     * @param event outbox单条消息记录
     */
    private void dispatchOne(OutboxEvent event) {
        // 绑定消息唯一ID，用于MQ Confirm回调关联本条消息
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        try {
            // 发送消息到指定交换机、路由键，携带消息体payload
            rabbitTemplate.convertAndSend(
                    event.getExchangeName(),
                    event.getRoutingKey(),
                    event.getPayload(),
                    // 后置处理器，给消息设置MessageId，方便链路追踪，传到MQ
                    message -> {
                        message.getMessageProperties().setMessageId(event.getEventId());
                        return message;
                    },
                    /*
                    * 只在生产者当前 JVM 内部使用，MQ 本身不会存储，它专门配合 publisher-confirm-type: correlated 发布者确认机制：
                    * 专门收 MQ 确认回执、判断投递成败用
                    * */
                    correlationData
            );
            // 阻塞等待MQ返回确认结果，最多等待5秒，超时抛出异常
            // 获取的是一个异步回调的 Future 异步结果对象，用来阻塞等待 RabbitMQ 异步返回的 ACK/NACK 确认回执再操作更新数据库
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // 判断MQ是否正常落盘ACK
            if (!confirm.ack()) {
                throw new IllegalStateException("Broker 未确认消息: " + confirm.reason());
            }
            // 判断是否发生路由失败 交换机存在但找不到绑定队列
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "消息无法路由到队列: "
                                + correlationData.getReturned().getReplyText()
                );
            }
            // 全部校验通过，更新数据库状态为【已发送】
            outboxEventMapper.markSent(event.getId());
        } catch (Exception e) {
            markFailed(event, e);
        }
    }

    /**
     * 发送失败后置处理：指数退避计算下次执行时间、截断异常信息、更新数据库失败记录
     * @param event 当前消息对象
     * @param exception 捕获的异常
     */
    private void markFailed(OutboxEvent event, Exception exception) {
        // 获取当前已重试次数，null则初始化为0
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        // 指数退避算法：delay = 2^retryCount 秒，最多限制2^8=256秒，再封顶300秒
        long delaySeconds = Math.min(
                MAX_RETRY_DELAY_SECONDS,
                1L << Math.min(retryCount, MAX_RETRY_SHIFT)
        );
        // 截取异常信息，避免超长字符串入库报错
        String error = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        // 调用Mapper更新：重试次数+1、设置下次可执行时间、记录错误日志
        outboxEventMapper.markFailed(
                event.getId(),
                LocalDateTime.now().plusSeconds(delaySeconds),
                error
        );
        log.error(
                "Outbox 消息发送失败，eventId={}，将在 {} 秒后重试",
                event.getEventId(),
                delaySeconds,
                exception
        );
    }
}
