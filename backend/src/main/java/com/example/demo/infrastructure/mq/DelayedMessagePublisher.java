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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void scheduleResourcePurge(ResourcePurgeDomainEvent domainEvent) {
        publish(
                RabbitMqConfig.RESOURCE_PURGE_ROUTING_KEY,
                domainEvent.event(),
                domainEvent.delayMilliseconds(),
                "RESOURCE_PURGE"
        );
    }

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

    private void publish(
            String routingKey,
            Object event,
            long delayMilliseconds,
            String messageType
    ) {
        String messageId = UUID.randomUUID().toString();
        try {
            String payload = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.DELAYED_EXCHANGE,
                    routingKey,
                    payload,
                    message -> {
                        message.getMessageProperties().setHeader(
                                "x-delay",
                                Math.max(delayMilliseconds, 0)
                        );
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    },
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
