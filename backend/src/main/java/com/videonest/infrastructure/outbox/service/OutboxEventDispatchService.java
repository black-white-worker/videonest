package com.videonest.infrastructure.outbox.service;

/**
 * 负责领取并投递当前可处理的 Outbox 事件。
 */
public interface OutboxEventDispatchService {

    /**
     * 执行可就绪状态Outbox消息的全量投递逻辑
     */
    void dispatchReadyEvents();
}
