package com.example.demo.common.exception;

public class MessagePublishException extends RuntimeException {

    private final String messageType;

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
