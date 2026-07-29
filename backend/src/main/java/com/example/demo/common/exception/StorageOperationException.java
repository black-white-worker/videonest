package com.example.demo.common.exception;

public class StorageOperationException extends RuntimeException {

    private final String operation;
    private final boolean retryable;

    public StorageOperationException(
            String operation,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        super(message, cause);
        this.operation = operation;
        this.retryable = retryable;
    }

    public String getOperation() {
        return operation;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
