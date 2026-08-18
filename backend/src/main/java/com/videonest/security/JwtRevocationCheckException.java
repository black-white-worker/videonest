package com.videonest.security;

/**
 * JWT吊销校验自定义异常类
 * 继承运行时异常RuntimeException，属于非受检异常，不需要在方法上显式throws抛出
 * 用于在校验JWT令牌是否被注销/拉黑时抛出业务专属异常，方便全局异常处理器捕获区分错误类型
 */
public class JwtRevocationCheckException extends RuntimeException {

    /**
     * @param message 异常自定义提示信息，给前端或日志展示错误描述
     * @param cause 原始根异常对象，保存异常堆栈信息，方便排查底层报错原因
     */
    public JwtRevocationCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
