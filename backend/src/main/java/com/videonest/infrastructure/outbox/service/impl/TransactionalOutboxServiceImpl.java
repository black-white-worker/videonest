package com.videonest.infrastructure.outbox.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videonest.common.exception.MessagePublishException;
import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import com.videonest.infrastructure.outbox.mapper.OutboxEventMapper;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class TransactionalOutboxServiceImpl implements TransactionalOutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxServiceImpl(
            OutboxEventMapper outboxEventMapper,
            ObjectMapper objectMapper
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void append(
            String eventId,
            String eventType,
            String exchange,
            String routingKey,
            Object payload
    ) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId == null ? UUID.randomUUID().toString() : eventId);
        event.setEventType(eventType);
        event.setExchangeName(exchange);
        event.setRoutingKey(routingKey);
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new MessagePublishException(eventType, "事务消息序列化失败", e);
        }
        outboxEventMapper.insert(event);
    }
}
