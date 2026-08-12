package com.videonest.module.notification.service;

public interface NotificationMessageConsumerService {

    /**
     * 消费队列消息
     * @param message 从消息队列中接收的原始字符串消息（一般为JSON格式）
     */
    void consume(String message);
}
