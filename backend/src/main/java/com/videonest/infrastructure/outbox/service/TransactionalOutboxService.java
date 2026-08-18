package com.videonest.infrastructure.outbox.service;

/**
 * 在当前数据库事务中持久化待投递事件。
 *
 */
public interface TransactionalOutboxService {

    void append(
            String eventId,
            String eventType,
            String exchange,
            String routingKey,
            Object payload
    );
}
