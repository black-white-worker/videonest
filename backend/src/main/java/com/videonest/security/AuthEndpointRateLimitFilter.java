package com.videonest.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * 认证接口专用限流过滤器。
 *
 * <p>覆盖三个“不需要登录态但会消耗凭证或账号”的 POST 接口：
 * 登录 {@code /api/auth/login}、注册 {@code /api/auth/register}、续期 {@code /api/auth/refresh}。
 * refresh 是唯一“用凭证换凭证”的入口，不限流等于开放了一个低成本的令牌探测/暴力轮换通道，
 * 因此它必须和登录、注册同一套策略，只是阈值更宽（正常客户端 15 分钟才续期一次）。
 *
 * <p>技术方案：Redis + Lua 固定窗口计数，原子自增避免并发超发；按接口分桶，互不影响。
 * Redis 故障时限流降级为放行（不能因为限流组件故障阻断全部登录），但每次降级都计入
 * {@code videonest.auth.rate_limit{result="degraded"}}，避免“静默放行”。
 */
@Component
@Slf4j
public class AuthEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String REFRESH_PATH = "/api/auth/refresh";

    private static final String KEY_PREFIX = "videonest:rate-limit:auth:";
    private static final String METRIC_NAME = "videonest.auth.rate_limit";

    /*
     * Redis Lua 限流脚本（固定窗口）：
     * 1. KEYS[1]：限流 Key；
     * 2. ARGV[1]：窗口秒数；
     * INCR 后若为首次创建则设置 EXPIRE，返回当前计数供 Java 侧比较阈值。
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
    private final Counter rejected;
    private final Counter degraded;

    public AuthEndpointRateLimitFilter(
            StringRedisTemplate redisTemplate,
            AuthRateLimitProperties properties,
            SecurityErrorResponseWriter responseWriter,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.responseWriter = responseWriter;
        this.rejected = meterRegistry.counter(METRIC_NAME, "result", "rejected");
        this.degraded = meterRegistry.counter(METRIC_NAME, "result", "degraded");
    }

    /** 单个接口的限流策略：动作名（用作 Redis 分桶）、窗口内最大次数、窗口秒数。 */
    private record LimitPolicy(String action, int maxRequests, int windowSeconds) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 配置总开关未开启，或不是 POST，直接跳过
        if (!properties.isEnabled() || !"POST".equals(request.getMethod())) {
            return true;
        }
        return policyFor(request.getServletPath()) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        LimitPolicy policy = policyFor(request.getServletPath());
        if (policy == null) {
            // shouldNotFilter 已经过滤过，这里只是兜底，避免配置变更时路径漏判
            filterChain.doFilter(request, response);
            return;
        }

        // 按动作分桶：登录、注册、续期的计数互不影响
        String key = KEY_PREFIX + policy.action() + ":" + sha256(request.getRemoteAddr());

        try {
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    Integer.toString(policy.windowSeconds())
            );
            if (count != null && count > policy.maxRequests()) {
                rejected.increment();
                // 告诉客户端多少秒后重试
                response.setHeader("Retry-After", Integer.toString(policy.windowSeconds()));
                responseWriter.write(response, 429, "请求过于频繁，请稍后重试");
                return;
            }
        } catch (RuntimeException e) {
            // 限流依赖故障时放行，避免 Redis 短暂不可用阻断全部登录；同时计数，便于运维发现降级窗口。
            degraded.increment();
            log.error("认证接口限流检查失败，action={}，降级为放行", policy.action(), e);
        }

        filterChain.doFilter(request, response);
    }

    /** 路径到限流策略的映射；返回 null 表示该路径不受本过滤器约束。 */
    private LimitPolicy policyFor(String path) {
        if (LOGIN_PATH.equals(path)) {
            return new LimitPolicy("login",
                    properties.getLoginMaxRequests(), properties.getLoginWindowSeconds());
        }
        if (REGISTER_PATH.equals(path)) {
            return new LimitPolicy("register",
                    properties.getRegisterMaxRequests(), properties.getRegisterWindowSeconds());
        }
        if (REFRESH_PATH.equals(path)) {
            return new LimitPolicy("refresh",
                    properties.getRefreshMaxRequests(), properties.getRefreshWindowSeconds());
        }
        return null;
    }

    /**
     * 对 IP 做 SHA-256 并截取前 12 位十六进制：
     * 避免 IP 特殊字符导致 Redis Key 非法、过长泄露真实客户端 IP。
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
