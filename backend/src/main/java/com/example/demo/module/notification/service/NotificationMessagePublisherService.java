package com.example.demo.module.notification.service;

import com.example.demo.module.notification.event.NotificationDomainEvent;

public interface NotificationMessagePublisherService {

    void publish(NotificationDomainEvent domainEvent);
}