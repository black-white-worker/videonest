package com.videonest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定义"权限不足处理器"，实现 AccessDeniedHandler 接口。
 * 作用：当用户已经登录，但角色/权限不够访问某个接口时，
 * 不再走 Spring Security 默认的跳转页面，而是返回一段统一格式的 JSON。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;

    public RestAccessDeniedHandler(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    /**
     * 重写Spring Security接口中的 handle 方法
     * @param request              触发异常的原始请求对象
     * @param response             用来返回给客户端的响应对象
     * @param accessDeniedException 权限不足异常对象（可拿到具体原因，这里没用到）
     * @throws IOException         写响应流失败时抛出
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        responseWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "没有权限执行该操作");
    }
}
