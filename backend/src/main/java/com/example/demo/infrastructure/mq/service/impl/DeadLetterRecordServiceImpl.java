package com.example.demo.infrastructure.mq.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.MessagePublishException;
import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;
import com.example.demo.infrastructure.mq.mapper.DeadLetterRecordMapper;
import com.example.demo.infrastructure.mq.service.DeadLetterRecordService;
import com.example.demo.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 死信记录服务实现类
 * 完成死信记录落库、分页查询、重试、忽略完整逻辑
 * 当MQ消息消费失败进入死信队列后，调用record落库；支持后台人工重试/忽略死信
 */
@Slf4j
@Service
public class DeadLetterRecordServiceImpl implements DeadLetterRecordService {

    private final DeadLetterRecordMapper recordMapper;
    private final RabbitTemplate rabbitTemplate;

    public DeadLetterRecordServiceImpl(
            DeadLetterRecordMapper recordMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.recordMapper = recordMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 保存死信记录到数据库
     * 死信队列消费者收到死信消息时调用此方法，把失败消息持久化数据库
     * @param queueName 原始来源队列名称
     * @param messageType 消息业务类型，用来匹配重试时交换机、routingKey
     * @param businessId 业务id，关联业务数据
     * @param payload 原始消息JSON报文，重试的时候直接复用这个报文发送
     * @param failureReason 消费失败异常原因
     */
    @Override
    public void record(
            String queueName,
            String messageType,
            String businessId,
            String payload,
            String failureReason
    ) {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setQueueName(queueName);             //来源队列名字
        record.setMessageType(messageType);
        record.setBusinessId(businessId);
        // 保存原始消息报文，重试的时候读取这个payload重新发送
        record.setPayload(payload);
        // 调用truncate方法截断异常信息，防止超长字符串入库报错，最多存500字符
        record.setFailureReason(truncate(failureReason, 500));
        // 设置死信状态：PENDING 待处理（等待人工重试/忽略）
        record.setStatus("PENDING");
        // MyBatis‑Plus插入一条记录，id数据库自增回填到record对象
        recordMapper.insert(record);
        log.error(
                "死信已落库，recordId={}，messageType={}，businessId={}",
                record.getId(),
                messageType,
                businessId
        );
    }

    /**
     * 分页查询死信记录列表
     * @param page 当前页码
     * @param size 每页条数
     * @param status 状态过滤条件，可以为null
     * @return PageResult 统一分页返回对象给controller
     */
    @Override
    public PageResult<DeadLetterRecord> list(
            long page,
            long size,
            String status
    ) {
        // 构建MyBatis‑Plus Lambda查询条件对象
        LambdaQueryWrapper<DeadLetterRecord> query =
                new LambdaQueryWrapper<DeadLetterRecord>()
                        // eq(boolean condition,列，值)：第一个参数为true才拼接where条件；
                        // StringUtils.hasText(status) status不为空才拼接 status = ? ，status为null直接不增加该条件
                        .eq(
                                StringUtils.hasText(status),
                                DeadLetterRecord::getStatus,
                                status
                        )
                        // 根据创建时间倒序，最新的死信排在最上面
                        .orderByDesc(DeadLetterRecord::getCreateTime)
                        // 根据主键id倒序，时间相同用id排序
                        .orderByDesc(DeadLetterRecord::getId);
        IPage<DeadLetterRecord> pageData = recordMapper.selectPage(
                new Page<>(page, size),
                query
        );
        // 调用静态of方法把pageData转为项目统一的PageResult返回给上层Controller
        return PageResult.of(pageData);
    }

    /**
     * 人工重试死信消息，发送回相应的业务队列
     * 将数据库中PENDING状态死信，重新发送回MQ，更新记录状态为RETRIED
     * @param id 死信记录主键id
     */
    @Override
    @Transactional
    public void retry(Long id) {
        DeadLetterRecord record = requirePendingRecord(id);
        String messageId = UUID.randomUUID().toString();
        Route route = route(record.getMessageType());
        try {
            rabbitTemplate.convertAndSend(
                    route.exchange(),
                    route.routingKey(),
                    record.getPayload(),        // 原始消息报文
                    message -> {
                        // MessagePostProcessor 消息后置处理器，消息转换完成发送前修改消息属性
                        message.getMessageProperties().setMessageId(messageId);
                        // 如果是延迟消息类型，设置x‑delay头部，这里设置0，不做延迟，立即发送
                        if (route.delayed()) {
                            message.getMessageProperties().setHeader("x-delay", 0);
                        }
                        return message;
                    },
                    new CorrelationData(messageId)
            );
        } catch (AmqpException e) {
            throw new MessagePublishException(
                    record.getMessageType(),
                    "死信消息重新投递失败",
                    e
            );
        }

        // 管理员Id
        Long operatorId = SecurityUtils.getCurrentUser().userId();
        // 调用mapper自定义方法，乐观锁更新状态，条件：id并且status=PENDING；更新为RETRIED，设置操作人
        // 返回受影响行数，如果0行代表记录已经被别的线程修改（状态已经不是PENDING），抛出409冲突异常
        if (recordMapper.markHandled(id, "RETRIED", operatorId) == 0) {
            throw new BusinessException(409, "死信记录状态已发生变化");
        }
        log.info(
                "管理员重新投递死信成功，recordId={}，messageId={}，operatorId={}",
                id,
                messageId,
                operatorId
        );
    }

    /**
     * 人工忽略死信，不再重试，更新状态IGNORED
     * @param id 死信记录主键id
     */
    @Override
    public void ignore(Long id) {
        // 校验记录存在并且状态PENDING待处理
        requirePendingRecord(id);
        Long operatorId = SecurityUtils.getCurrentUser().userId();
        if (recordMapper.markHandled(id, "IGNORED", operatorId) == 0) {
            throw new BusinessException(409, "死信记录状态已发生变化");
        }
        log.info("管理员忽略死信成功，recordId={}，operatorId={}", id, operatorId);
    }

    /**
     * 私有工具方法：校验死信记录，必须存在并且状态PENDING待处理
     * @param id 死信记录id
     * @return 查询到的死信记录实体
     */
    private DeadLetterRecord requirePendingRecord(Long id) {
        DeadLetterRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(404, "死信记录不存在");
        }
        if (!"PENDING".equals(record.getStatus())) {
            throw new BusinessException(409, "死信记录已经处理");
        }
        return record;
    }

    /**
     * 根据消息类型messageType，匹配对应的交换机、routingKey、是否延迟消息
     * @param messageType 消息业务类型
     * @return Route记录对象，包含exchange routingKey delayed标记
     */
    private Route route(String messageType) {
        return switch (messageType) {
            case "VIDEO_PROCESS" -> new Route(
                    RabbitMqConfig.VIDEO_PROCESS_EXCHANGE,
                    RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY,
                    false
            );
            case "NOTIFICATION" -> new Route(
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY,
                    false
            );
            case "REVIEW_TIMEOUT" -> new Route(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    RabbitMqConfig.REVIEW_TIMEOUT_ROUTING_KEY,
                    true
            );
            case "RESOURCE_PURGE" -> new Route(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    RabbitMqConfig.RESOURCE_PURGE_ROUTING_KEY,
                    true
            );
            default -> throw new BusinessException(400, "不支持重投该类型的死信");
        };
    }

    /**
     * 字符串截断工具，防止超长异常信息入库报错
     * @param value 原始字符串
     * @param length 最大允许长度
     * @return 截断之后字符串
     */
    private String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    /**
     * Java16私有内部Record，不可变数据载体，封装路由三元组：交换机、路由key、是否延迟消息
     * record自动生成构造器、访问方法exchange()、routingKey()、delayed()，equals、hashCode、toString
     * 仅当前类内部使用，不需要单独创建DTO类
     */
    private record Route(String exchange, String routingKey, boolean delayed) {
    }
}
