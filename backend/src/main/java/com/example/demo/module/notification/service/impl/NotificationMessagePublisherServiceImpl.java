package com.example.demo.module.notification.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.common.exception.MessagePublishException;
import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.service.NotificationMessagePublisherService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.AmqpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Service
@Slf4j
public class NotificationMessagePublisherServiceImpl
        implements NotificationMessagePublisherService {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public NotificationMessagePublisherServiceImpl(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(NotificationDomainEvent domainEvent) {
        String messageId = UUID.randomUUID().toString();
        try {
            String message = objectMapper.writeValueAsString(
                    domainEvent.notificationEvent()
            );

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY,
                    message,
                    amqpMessage -> {
                        amqpMessage.getMessageProperties().setMessageId(messageId);
                        return amqpMessage;
                    },
                    new CorrelationData(messageId)
            );
            log.info(
                    "通知消息发送成功，eventId={}，type={}，messageId={}",
                    domainEvent.notificationEvent().eventId(),
                    domainEvent.notificationEvent().type(),
                    messageId
            );
        } catch (JsonProcessingException e) {
            throw new MessagePublishException(
                    "NOTIFICATION",
                    "通知消息序列化失败",
                    e
            );
        } catch (AmqpException e) {
            throw new MessagePublishException(
                    "NOTIFICATION",
                    "通知消息发送失败",
                    e
            );
        }
    }
}
