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

/**
 * 认证接口专用限流过滤器
 * 功能：对登录 /api/auth/login、注册 /api/auth/register 两个POST接口做IP维度频率限制
 * 防止暴力破解密码、恶意批量注册、接口刷压
 * 技术方案：Redis + Lua脚本 滑动窗口（固定窗口）限流，原子操作避免并发超计数
 * 继承OncePerRequestFilter：保证一次HTTP请求整个链路只执行一次过滤逻辑，避免重复执行
 */
@Component
@Slf4j
public class AuthEndpointRateLimitFilter extends OncePerRequestFilter {

    /*
     * 静态常量：Redis Lua限流脚本
     * 采用固定时间窗口限流算法，存为全局静态只初始化一次，节省内存
     * 1. KEYS[1]：Redis限流Key
     * 2. ARGV[1]：窗口过期秒数
     * 3. redis.call('INCR', KEYS[1]) 对key自增1，首次创建key时值为1
     * 4. 如果是第一次创建（current==1），给key设置过期时间 EXPIRE
     * 5. 返回当前计数，供Java代码判断是否超限
     */
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

    /**
     * 判断当前请求是否需要跳过过滤器
     * 返回true = 不执行过滤，直接放行；返回false = 执行doFilterInternal限流逻辑
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 配置总开关未开启  OR  请求方法不是POST，直接跳过限流
        if (!properties.isEnabled() || !"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        return !path.equals("/api/auth/login")
                && !path.equals("/api/auth/register");
    }

    /**
     * 过滤器核心执行逻辑：真正做限流判断的主方法
     * @param request 请求对象
     * @param response 响应对象
     * @param filterChain 过滤器链，放行调用后续过滤器/Controller
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // 判断是登录还是注册
        boolean login = request.getServletPath().endsWith("/login");
        // 根据接口类型读取配置的最大允许请求次数
        int maxRequests = login
                ? properties.getLoginMaxRequests()
                : properties.getRegisterMaxRequests();
        // 根据接口类型读取限流窗口时间
        int windowSeconds = login
                ? properties.getLoginWindowSeconds()
                : properties.getRegisterWindowSeconds();
        String action = login ? "login" : "register";
        // 哈希IP目的：避免IP特殊字符导致Redis Key非法、过长泄露真实客户端IP
        String key = "videonest:rate-limit:auth:" + action + ":"
                + sha256(request.getRemoteAddr());

        try {
            // 执行Redis Lua原子限流脚本
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    Integer.toString(windowSeconds)
            );
            if (count != null && count > maxRequests) {
                // 设置响应头 Retry-After 告诉客户端多少秒后重试
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

        // 未触发限流，放行请求进入后续过滤器和控制器
        filterChain.doFilter(request, response);
    }

    /**
     * 私有工具方法：对IP字符串做SHA256哈希，并截取前12位十六进制字符
     * @param value 原始IP字符串
     * @return 缩短后的哈希字符串
     */
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
