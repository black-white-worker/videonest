package com.videonest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    public RestAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        responseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
    }
}
