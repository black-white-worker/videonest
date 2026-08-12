package com.videonest.security;

public class JwtRevocationCheckException extends RuntimeException {

    public JwtRevocationCheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
