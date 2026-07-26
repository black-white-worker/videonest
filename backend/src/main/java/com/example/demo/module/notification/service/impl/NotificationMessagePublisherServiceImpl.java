package com.example.demo.module.notification.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.service.NotificationMessagePublisherService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
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
        try {
            String message = objectMapper.writeValueAsString(
                    domainEvent.notificationEvent()
            );

            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY,
                    message
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通知消息序列化失败", e);
        }
    }
}