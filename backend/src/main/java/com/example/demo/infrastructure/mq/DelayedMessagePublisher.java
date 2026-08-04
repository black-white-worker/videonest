package com.example.demo.infrastructure.mq;

import com.example.demo.common.exception.MessagePublishException;
import com.example.demo.module.video.event.ResourcePurgeDomainEvent;
import com.example.demo.module.video.event.ResourcePurgeEvent;
import com.example.demo.module.video.event.ReviewTimeoutEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * 延迟消息发送器
 * 基于RabbitMQ延迟插件实现延迟消息投递，统一封装延迟消息发布逻辑
 */
@Slf4j
@Service
public class DelayedMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public DelayedMessagePublisher(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送【审核超时延迟消息】
     * @param event 审核超时事件实体
     * @param delayMilliseconds 延迟毫秒数
     */
    public void scheduleReviewTimeout(
            ReviewTimeoutEvent event,
            long delayMilliseconds
    ) {
        publish(
                RabbitMqConfig.REVIEW_TIMEOUT_ROUTING_KEY,
                event,
                delayMilliseconds,
                "REVIEW_TIMEOUT"
        );
    }

    /**
     * 事务监听器：领域事件触发后，事务提交成功才发送延迟清理资源消息
     * @ TransactionalEventListener 事务事件监听注解
     * phase = TransactionPhase.AFTER_COMMIT：只有当前数据库事务提交成功后，才执行方法发送消息
     * 作用：避免事务回滚后消息依然发出导致数据不一致
     * @param domainEvent 资源清理领域事件
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void scheduleResourcePurge(ResourcePurgeDomainEvent domainEvent) {
        publish(
                RabbitMqConfig.RESOURCE_PURGE_ROUTING_KEY,
                domainEvent.event(),
                domainEvent.delayMilliseconds(),
                "RESOURCE_PURGE"
        );
    }

    /**
     * 直接发送资源清理延迟消息（外部主动调用，不受事务控制）
     * @param event 资源清理事件
     * @param delayMilliseconds 延迟毫秒
     */
    public void scheduleResourcePurge(
            ResourcePurgeEvent event,
            long delayMilliseconds
    ) {
        publish(
                RabbitMqConfig.RESOURCE_PURGE_ROUTING_KEY,
                event,
                delayMilliseconds,
                "RESOURCE_PURGE"
        );
    }

    /**
     * 【通用延迟消息发布底层方法】所有延迟消息统一走这个方法
     * @param routingKey 路由key
     * @param event 待发送事件对象
     * @param delayMilliseconds 延迟毫秒数
     * @param messageType 消息类型标识，用于日志、异常区分业务
     */
    private void publish(
            String routingKey,
            Object event,
            long delayMilliseconds,
            String messageType
    ) {
        String messageId = UUID.randomUUID().toString();
        try {
            // 将事件对象序列化为JSON字符串，作为MQ消息载荷
            String payload = objectMapper.writeValueAsString(event);
            // rabbitTemplate核心发送方法：发送消息到延迟交换机
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    routingKey,
                    payload,
                    // Message后置处理器：在消息发送前修改消息属性
                    message -> {
                        // 设置延迟头 x-delay：rabbitmq延迟插件依赖此header控制延迟时间（单位ms）
                        message.getMessageProperties().setHeader(
                                "x-delay",
                                Math.max(delayMilliseconds, 0)
                        );
                        // 设置最小id
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    },
                    // CorrelationData：关联数据，用于生产者确认（publisher-confirm-type）
                    /*
                    * 先生成 messageId = UUID
                    * 再创建 new CorrelationData(messageId) 随同消息一起发送
                    * RabbitTemplate 发送消息到延迟交换机
                    * RabbitMQ 服务端回复 ack/nack
                    * 触发 ConfirmCallback，传入同一个 CorrelationData
                    * 代码通过 id 定位是哪一条消息投递成功 / 失败，进行日志、补偿处理
                    * */
                    new CorrelationData(messageId)
            );
            log.info(
                    "延迟消息发送成功，messageType={}，messageId={}，delayMilliseconds={}",
                    messageType,
                    messageId,
                    delayMilliseconds
            );
        } catch (JsonProcessingException e) {
            throw new MessagePublishException(
                    messageType,
                    "延迟消息序列化失败",
                    e
            );
        } catch (AmqpException e) {
            throw new MessagePublishException(
                    messageType,
                    "延迟消息发送到 RabbitMQ 失败",
                    e
            );
        }
    }
}
