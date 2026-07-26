package com.example.demo.module.notification.service;

public interface NotificationMessageConsumerService {

    void consume(String message) throws Exception;
}