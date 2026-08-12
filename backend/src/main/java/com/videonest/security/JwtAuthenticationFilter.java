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

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtRevocationService jwtRevocationService;
    private final SecurityErrorResponseWriter responseWriter;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            JwtRevocationService jwtRevocationService,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtRevocationService = jwtRevocationService;
        this.responseWriter = responseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();

        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authorization =
                request.getHeader("Authorization");

        // 没有 Token，交给后面的 Security 判断是否需要登录
        if (!StringUtils.hasText(authorization)
                || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

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
            Claims claims = jwtTokenProvider.parseToken(token);

            if (jwtRevocationService.isRevoked(claims)) {
                responseWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token 已失效"
                );
                return;
            }

            Long userId = claims.get("userId", Long.class);
            String username = claims.getSubject();
            String role = claims.get("role", String.class);

            if (userId == null || !StringUtils.hasText(username)) {
                responseWriter.write(
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "Token 信息不完整"
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

            SecurityContextHolder
                    .getContext()
                    .setAuthentication(authentication);

            log.debug(
                    "JWT 认证成功，userId={}，username={}，role={}",
                    userId,
                    username,
                    role
            );

            filterChain.doFilter(request, response);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn(
                    "JWT 解析失败，uri={}，reason={}",
                    request.getRequestURI(),
                    e.getMessage()
            );

            SecurityContextHolder.clearContext();

            responseWriter.write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Token 无效或已过期"
            );
        } catch (JwtRevocationCheckException e) {
            log.error("JWT 撤销状态校验失败，uri={}", request.getRequestURI(), e);
            SecurityContextHolder.clearContext();
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "认证服务暂时不可用，请稍后重试"
            );
        }
    }
}
