package com.example.demo.common.exception;

import com.example.demo.common.api.ApiResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局统一异常处理器
 * 拦截Controller层向外抛出、未被捕获的异常，统一包装成ApiResponse返回前端
 */
//@RestControllerAdvice = @ControllerAdvice + @ResponseBody
//@ControllerAdvice：作用于所有Controller，监听Controller抛出的异常
//@ResponseBody：方法返回值自动序列化为JSON，不需要每个方法额外加

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**捕获自定义业务异常 BusinessException*/
    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        //fail为ApiResponse自定义的方法
        log.warn("业务处理失败，code={}，message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /**请求参数校验失败*/
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldError() == null
                ? "请求参数校验失败"
                : e.getBindingResult()//拿到本次校验结果对象，存放所有校验失败的字段、字段名、错误提示信息
                .getFieldError()//取第一个校验失败的字段错误信息
                .getDefaultMessage();//取出在 DTO 注解里写的提示文字
        log.warn("请求体参数校验失败，message={}", message);
        return ApiResponse.fail(400, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(
            ConstraintViolationException e
    ) {
        log.warn("请求参数约束校验失败，message={}", e.getMessage());
        return ApiResponse.fail(400, e.getMessage());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ApiResponse<Void> handleMalformedRequest(Exception e) {
        log.warn("请求格式或参数类型错误，type={}，message={}",
                e.getClass().getSimpleName(), e.getMessage());
        return ApiResponse.fail(400, "请求格式或参数类型错误");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleUploadSizeExceeded(
            MaxUploadSizeExceededException e
    ) {
        log.warn("上传文件超过服务端限制，maxUploadSize={}", e.getMaxUploadSize());
        return ApiResponse.fail(413, "上传文件超过服务端大小限制");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> handleDataIntegrityViolation(
            DataIntegrityViolationException e
    ) {
        log.warn("数据库唯一约束或数据完整性冲突", e);
        return ApiResponse.fail(409, "数据已存在或当前状态发生冲突");
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ApiResponse<Void> handleRedisConnectionFailure(
            RedisConnectionFailureException e
    ) {
        log.error("Redis 连接失败", e);
        return ApiResponse.fail(503, "缓存服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(DataAccessException.class)
    public ApiResponse<Void> handleDataAccessException(DataAccessException e) {
        log.error("数据库访问失败", e);
        return ApiResponse.fail(503, "数据库服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(StorageOperationException.class)
    public ApiResponse<Void> handleStorageOperation(
            StorageOperationException e
    ) {
        log.error(
                "对象存储操作失败，operation={}，retryable={}",
                e.getOperation(),
                e.isRetryable(),
                e
        );
        return ApiResponse.fail(503, "文件存储服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler({AmqpException.class, MessagePublishException.class})
    public ApiResponse<Void> handleMessageQueueFailure(RuntimeException e) {
        log.error("RabbitMQ 消息操作失败，type={}", e.getClass().getSimpleName(), e);
        return ApiResponse.fail(503, "消息服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ApiResponse<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("用户访问无权限资源，message={}", e.getMessage());
        return ApiResponse.fail(403, "没有权限执行该操作");
    }

    /**捕获所有其他未单独定义的异常*/
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("未分类的服务端异常，type={}", e.getClass().getName(), e);
        return ApiResponse.fail(500, "服务器内部异常");
    }
}
