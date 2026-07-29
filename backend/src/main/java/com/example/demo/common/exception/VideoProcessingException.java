package com.example.demo.common.exception;

public class VideoProcessingException extends RuntimeException {

    private final String failureType;

    public VideoProcessingException(
            String failureType,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = failureType;
    }

    public VideoProcessingException(String failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public String getFailureType() {
        return failureType;
    }
}
