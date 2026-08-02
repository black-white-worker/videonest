package com.example.demo.common.exception;

/**
 * 视频处理自定义运行时异常
 * 场景：FFmpeg视频转码、切片、缩略图、视频校验等任务失败时抛出
 */

public class VideoProcessingException extends RuntimeException {

    private final String failureType;

    /**
     * 携带底层原始异常cause
     * @param failureType 故障类型（例如 TRANSCODE_FAIL、THUMBNAIL_ERROR、FORMAT_INVALID）
     * @param message 异常描述信息
     * @param cause 底层原始异常（FFmpeg执行异常、IO异常等）
     */

    public VideoProcessingException(
            String failureType,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = failureType;
    }

    /**
     * 不携带底层原始异常
     * 适用于业务主动判定失败，没有底层抛出的异常对象场景
     * @param failureType 故障类型
     * @param message 异常描述信息
     */
    public VideoProcessingException(String failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    /**
     * Getter方法，外部获取故障类型
     * 全局异常处理器、告警、任务重试逻辑可以读取该字段做分支处理
     */
    public String getFailureType() {
        return failureType;
    }
}
