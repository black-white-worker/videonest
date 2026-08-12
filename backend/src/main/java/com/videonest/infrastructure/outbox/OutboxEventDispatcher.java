package com.videonest.infrastructure.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxEventDispatcher {

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxEventDispatcher(
            OutboxEventMapper outboxEventMapper,
            RabbitTemplate rabbitTemplate
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.dispatch-interval-milliseconds:1000}")
    public void dispatch() {
        outboxEventMapper.recoverStaleProcessing();
        for (OutboxEvent event : outboxEventMapper.selectReady(50)) {
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
                    .get(5, TimeUnit.SECONDS);
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
            int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
            long delaySeconds = Math.min(300, 1L << Math.min(retryCount, 8));
            String error = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            if (error.length() > 500) {
                error = error.substring(0, 500);
            }
            outboxEventMapper.markFailed(
                    event.getId(),
                    LocalDateTime.now().plusSeconds(delaySeconds),
                    error
            );
            log.error("Outbox 消息发送失败，eventId={}，将在 {} 秒后重试",
                    event.getEventId(), delaySeconds, e);
        }
    }
}
