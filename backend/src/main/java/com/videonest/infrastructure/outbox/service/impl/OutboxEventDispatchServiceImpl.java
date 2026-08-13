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

@Service
@Slf4j
public class OutboxEventDispatchServiceImpl implements OutboxEventDispatchService {

    private static final int DISPATCH_BATCH_SIZE = 50;
    private static final int CONFIRM_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRY_DELAY_SECONDS = 300;
    private static final int MAX_RETRY_SHIFT = 8;
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

    @Override
    public void dispatchReadyEvents() {
        outboxEventMapper.recoverStaleProcessing();
        for (OutboxEvent event : outboxEventMapper.selectReady(DISPATCH_BATCH_SIZE)) {
            if (outboxEventMapper.claim(event.getId()) == 0) {
                continue;
            }
            dispatchOne(event);
        }
    }

    private void dispatchOne(OutboxEvent event) {
        CorrelationData correlationData = new CorrelationData(event.getEventId());
        try {
            rabbitTemplate.convertAndSend(
                    event.getExchangeName(),
                    event.getRoutingKey(),
                    event.getPayload(),
                    message -> {
                        message.getMessageProperties().setMessageId(event.getEventId());
                        return message;
                    },
                    correlationData
            );
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new IllegalStateException("Broker 未确认消息: " + confirm.reason());
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException(
                        "消息无法路由到队列: "
                                + correlationData.getReturned().getReplyText()
                );
            }
            outboxEventMapper.markSent(event.getId());
        } catch (Exception e) {
            markFailed(event, e);
        }
    }

    private void markFailed(OutboxEvent event, Exception exception) {
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        long delaySeconds = Math.min(
                MAX_RETRY_DELAY_SECONDS,
                1L << Math.min(retryCount, MAX_RETRY_SHIFT)
        );
        String error = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
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
