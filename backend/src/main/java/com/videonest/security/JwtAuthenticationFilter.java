package com.videonest.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * JWT 令牌解析与自动认证过滤器
 * 1. 放行登录、注册两个无需token的接口
 * 2. 从请求Header提取Bearer格式的JWT令牌
 * 3. 解析JWT、校验是否被手动吊销（退出登录拉黑）
 * 4. 从载荷取出用户ID、用户名、角色，封装自定义LoginUser
 * 5. 构造SpringSecurity认证对象存入上下文，后续接口直接判定已登录
 * 6. 捕获各类JWT异常，统一返回401未授权JSON响应
 * 执行位置：SpringSecurity过滤器链最前端，在权限校验之前完成身份认证
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern PUBLIC_VIDEO_GET_PATH = Pattern.compile(
            "^/api/videos(?:/hot|/\\d+|/\\d+/comments|/\\d+/comments/\\d+/replies)?$"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtRevocationService jwtRevocationService;
    private final TokenVersionService tokenVersionService;
    private final JwtProperties jwtProperties;
    private final SecurityErrorResponseWriter responseWriter;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            JwtRevocationService jwtRevocationService,
            TokenVersionService tokenVersionService,
            JwtProperties jwtProperties,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtRevocationService = jwtRevocationService;
        this.tokenVersionService = tokenVersionService;
        this.jwtProperties = jwtProperties;
        this.responseWriter = responseWriter;
    }

    /**
     * 判断当前请求是否跳过本JWT过滤器
     * return true = 跳过过滤，直接放行
     * return false = 执行doFilterInternal做JWT校验
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/logout")
                || (request.getMethod().equals("GET")
                    && (path.equals("/api/categories") || PUBLIC_VIDEO_GET_PATH.matcher(path).matches()));
    }

    /**
     * 过滤器核心执行方法：JWT完整校验、解析、存入Security上下文主逻辑
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 从请求头中获取 Authorization 字段，标准JWT存放Header
        String authorization =
                request.getHeader("Authorization");

        // 没有 Token，交给后面的 Security 判断是否需要登录
        if (!StringUtils.hasText(authorization)
                || !authorization.startsWith("Bearer ")) {
            // 无token直接放行，交给SpringSecurity后续过滤器处理匿名访问
            filterChain.doFilter(request, response);
            return;
        }

        // 截取Bearer后面真正的token字符串，"Bearer "固定7个字符
        String token = authorization.substring(7).trim();

        if (!StringUtils.hasText(token)) {
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Token 不能为空"
            );
            return;
        }

        try {
            // 调用工具类解析JWT，拿到载荷Claims（存放userId、username、role）
            Claims claims = jwtTokenProvider.parseToken(token);

            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);

            // 校验Token数据完整性，防止恶意篡改导致关键信息缺失
            if (userId == null || !StringUtils.hasText(username)) {
                responseWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token 信息不完整"
                );
                return;
            }

            // 令牌版本号校验：改密 / 封号 / 全端下线后，该用户所有在途 access token 立即失效。
            // 版本号在进程内缓存 30 秒，正常请求不会因此多打一次 Redis。
            String tokenVersion = claims.get("ver", String.class);
            if (!tokenVersionService.matches(userId, tokenVersion)) {
                responseWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "登录状态已失效，请重新登录"
                );
                return;
            }

            // 旧的 jti 黑名单默认关闭：access token 只有 15 分钟，撤销由版本号负责。
            // 需要“单令牌秒级撤销”时打开 jwt.revocation-check-enabled，代价是每请求一次 Redis。
            if (jwtProperties.isRevocationCheckEnabled()
                    && jwtRevocationService.isRevoked(claims)) {
                responseWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token 已失效"
                );
                return;
            }

            if (!StringUtils.hasText(role)) {
                role = "USER";
            }

            role = role.toUpperCase(Locale.ROOT);

            LoginUser loginUser = new LoginUser(
                    userId,
                    username,
                    role
            );

            // 构建SpringSecurity全局认证令牌
            // 参数1：认证主体（我们自定义的LoginUser）
            // 参数2：密码凭证（JWT无密码，填null）
            // 参数3：权限集合，SpringSecurity规范角色必须加 ROLE_ 前缀
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            loginUser,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_" + role
                                    )
                            )
                    );

            // 认证对象只写入当前请求上下文，不把 JWT 状态存入 Session。
            // 将认证对象存入Security上下文，当前请求全局生效
            // 后续在Controller/Service通过SecurityUtils.getCurrentUser()就能拿到登录用户
            SecurityContextHolder
                    .getContext()
                    .setAuthentication(authentication);

            log.debug(
                    "JWT 认证成功，userId={}，username={}，role={}",
                    userId,
                    username,
                    role
            );

            // JWT全部校验通过，放行请求进入后续SpringSecurity权限过滤器、Controller
            filterChain.doFilter(request, response);

        } catch (JwtException | IllegalArgumentException e) {
            // 捕获JWT通用异常：签名错误、过期、篡改、格式非法、参数非法
            log.warn(
                    "JWT 解析失败，uri={}，reason={}",
                    request.getRequestURI(),
                    e.getMessage()
            );

            // 清空上下文防止脏数据残留
            SecurityContextHolder.clearContext();

            // 返回401未授权
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Token 无效或已过期"
            );
        } catch (JwtRevocationCheckException e) {
            // 单独捕获JWT吊销校验自定义异常（Redis查询黑名单故障
            log.error("JWT 撤销状态校验失败，uri={}", request.getRequestURI(), e);
            SecurityContextHolder.clearContext();
            // 返回503服务不可用，提示稍后重试
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "认证服务暂时不可用，请稍后重试"
            );
        }
    }
}
