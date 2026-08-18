package com.videonest.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videonest.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 安全错误响应写入工具类。
 * 把"状态码 + 错误信息"包装成统一 JSON 写回 HttpServletResponse。
 * 被 RestAccessDeniedHandler（403）、AuthenticationEntryPoint（401）等多个安全组件复用，
 * 避免每个类都重复写 setStatus / setContentType / writeValue 这套样板代码。
 */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 核心方法：向响应写入统一格式的错误 JSON。
     *
     * @param response HTTP 响应对象
     * @param status   HTTP 状态码
     * @param message  错误提示信息
     * @throws IOException 输出流写入失败时抛出
     */
    public void write(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 设置响应内容类型为 application/json，告诉前端"返回的是 JSON 数据"
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.fail(status, message)
        );
    }
}
