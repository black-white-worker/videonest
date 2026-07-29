package com.example.demo.common.exception;

import lombok.Getter;

/**继承RuntimeException后在调用的时候可以自由上抛，cntroller和service全没有处理这个异常
    就上抛到spring mvc，由spring mvc找注解@RestControllerAdvice 标注的全局异常处理器来处理*/
//@RestControllerAdvice再找对应匹配的@ExceptionHandler来处理

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}