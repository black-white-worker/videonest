package com.videonest.infrastructure.outbox.config;

import com.videonest.infrastructure.outbox.service.OutboxEventDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时触发 Outbox 事件投递，不承载具体发送逻辑。
 */
@Component
@ConditionalOnProperty(
        prefix = "outbox",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OutboxEventScheduler {

    private final OutboxEventDispatchService outboxEventDispatchService;

    public OutboxEventScheduler(OutboxEventDispatchService outboxEventDispatchService) {
        this.outboxEventDispatchService = outboxEventDispatchService;
    }

    @Scheduled(fixedDelayString = "${outbox.dispatch-interval-milliseconds:1000}")
    public void dispatch() {
        outboxEventDispatchService.dispatchReadyEvents();
    }
}
