package com.example.demo.common.exception;

/**
 * 存储操作自定义异常
 * 适用场景：MinIO / 文件存储 / 对象存储读写操作异常（上传、下载、删除、访问文件等）
 */

public class StorageOperationException extends RuntimeException {

    private final String operation;
    private final boolean retryable;

    /**
     * @param operation 存储操作标识
     * @param message 异常描述文本
     * @param retryable 是否可重试标志
     * @param cause 底层原始异常，保存异常堆栈
     */

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
