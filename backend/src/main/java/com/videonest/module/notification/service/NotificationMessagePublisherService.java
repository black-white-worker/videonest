package com.videonest.module.notification.service;

import com.videonest.module.notification.event.NotificationDomainEvent;

public interface NotificationMessagePublisherService {

    /**
     * 通知消息生产者（
     * 职责：将产生的通知事件发布到消息队列RabbitMQ，实现业务异步解耦
     */
    void publish(NotificationDomainEvent domainEvent);
}