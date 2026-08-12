package com.videonest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Component
@Slf4j
public class AuthEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>("""
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return current
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AuthRateLimitProperties properties;
    private final SecurityErrorResponseWriter responseWriter;

    public AuthEndpointRateLimitFilter(
            StringRedisTemplate redisTemplate,
            AuthRateLimitProperties properties,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled() || !"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !path.equals("/api/auth/login")
                && !path.equals("/api/auth/register");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean login = request.getServletPath().endsWith("/login");
        int maxRequests = login
                ? properties.getLoginMaxRequests()
                : properties.getRegisterMaxRequests();
        int windowSeconds = login
                ? properties.getLoginWindowSeconds()
                : properties.getRegisterWindowSeconds();
        String action = login ? "login" : "register";
        String key = "videonest:rate-limit:auth:" + action + ":"
                + sha256(request.getRemoteAddr());

        try {
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    Integer.toString(windowSeconds)
            );
            if (count != null && count > maxRequests) {
                response.setHeader("Retry-After", Integer.toString(windowSeconds));
                responseWriter.write(
                        response,
                        429,
                        "请求过于频繁，请稍后重试"
                );
                return;
            }
        } catch (RuntimeException e) {
            // 限流依赖故障时放行，避免 Redis 短暂不可用阻断全部用户登录，同时保留告警便于运维处理。
            log.error("认证接口限流检查失败，action={}，降级为放行", action, e);
        }

        filterChain.doFilter(request, response);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
