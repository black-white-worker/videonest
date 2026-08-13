package com.videonest.infrastructure.outbox.service;

/**
 * 负责领取并投递当前可处理的 Outbox 事件。
 */
public interface OutboxEventDispatchService {

    void dispatchReadyEvents();
}
