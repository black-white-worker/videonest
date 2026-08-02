package com.example.demo.common.exception;

public class MessagePublishException extends RuntimeException {

    private final String messageType;

    /**
     * 消息发布异常自定义异常类
     * 场景：RabbitMQ/消息队列发送消息失败时抛出该异常
     */
    /**
     * @param messageType 消息类型标识
     * @param message 异常描述信息
     * @param cause 原始异常对象（根异常，用于堆栈追踪）
     */
    public MessagePublishException(
            String messageType,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.messageType = messageType;
    }

    public String getMessageType() {
        return messageType;
    }
}
