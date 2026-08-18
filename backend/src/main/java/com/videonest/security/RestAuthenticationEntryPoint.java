package com.videonest.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 自定义"未认证入口点"，实现 AuthenticationEntryPoint 接口。
 * 触发时机：用户访问受保护资源，但**未携带有效身份凭证**（没登录 / Token 过期 / Token 无效）。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    public RestAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    /**
     * 重写接口的 commence 方法：Spring Security 检测到未认证时自动回调。
     * @param request      触发异常的请求对象
     * @param response     响应对象，用于返回错误信息
     * @param authException 认证异常对象
     * @throws IOException 写响应失败时抛出
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        responseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
    }
}
