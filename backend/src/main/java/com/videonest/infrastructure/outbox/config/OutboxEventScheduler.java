package com.videonest.infrastructure.outbox.config;

import com.videonest.infrastructure.outbox.service.OutboxEventDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox定时调度器
 * 职责：只负责定时触发调度，纯粹做定时触发器，消息查询、发送、状态更新等核心业务全部委托给OutboxEventDispatchService
 * 单一职责原则：定时规则与业务逻辑解耦
 */
/*
 * @ConditionalOnProperty 条件装配注解
 * 作用：只有满足配置条件时，才创建 OutboxEventScheduler 这个定时任务Bean
 * prefix = "outbox"：配置前缀，对应yml里outbox节点
 * name = "enabled"：读取配置项 outbox.enabled
 * havingValue = "true"：要求配置值等于true才生效
 * matchIfMissing = true：兜底规则，如果yml中完全没配置outbox.enabled，默认判定为满足条件，创建定时任务
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

    /**
     * 定时执行方法
     * fixedDelayString：上一次任务执行完毕后，间隔指定毫秒再执行下一次（避免任务堆积）
     * ${outbox.dispatch-interval-milliseconds:1000}：读取yml配置，未配置则默认1000毫秒
     */
    @Scheduled(fixedDelayString = "${outbox.dispatch-interval-milliseconds:1000}")
    public void dispatch() {
        outboxEventDispatchService.dispatchReadyEvents();
    }
}
