package com.videonest.module.notification.event;

/**
 * NotificationDomainEvent 领域事件对象。
 */
public record NotificationDomainEvent(
        NotificationEvent notificationEvent
) {
}
