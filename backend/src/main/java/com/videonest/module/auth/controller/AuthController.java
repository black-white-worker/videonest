package com.videonest.module.auth.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.auth.dto.LoginRequest;
import com.videonest.module.auth.dto.RegisterRequest;
import com.videonest.module.auth.service.AuthService;
import com.videonest.module.auth.vo.LoginResponse;
import com.videonest.security.JwtProperties;
import com.videonest.security.JwtRevocationService;
import com.videonest.security.LoginUser;
import com.videonest.security.RefreshTokenService;
import com.videonest.security.SecurityUtils;
import com.videonest.security.TokenVersionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证控制器。
 *
 * <p>登录返回短寿命 access token（放响应体，由前端存内存），同时通过
 * HttpOnly + SameSite=Lax 的 Cookie 下发 refresh token；续期、登出、全端下线都围绕这一对令牌展开。
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JwtRevocationService jwtRevocationService;
    private final RefreshTokenService refreshTokenService;
    private final TokenVersionService tokenVersionService;
    private final JwtProperties jwtProperties;

    public AuthController(
            AuthService authService,
            JwtRevocationService jwtRevocationService,
            RefreshTokenService refreshTokenService,
            TokenVersionService tokenVersionService,
            JwtProperties jwtProperties
    ) {
        this.authService = authService;
        this.jwtRevocationService = jwtRevocationService;
        this.refreshTokenService = refreshTokenService;
        this.tokenVersionService = tokenVersionService;
        this.jwtProperties = jwtProperties;
    }

    /** 用户注册。 */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success();
    }

    /**
     * 用户登录：返回 access token，同时种下 refresh Cookie。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        LoginResponse session = authService.login(request);
        RefreshTokenService.IssuedRefreshToken refresh =
                refreshTokenService.issue(session.getUserId(), RefreshTokenService.newDeviceId());
        writeRefreshCookie(response, refresh.token(), refresh.ttl());
        return ApiResponse.success(session);
    }

    /**
     * 续期：用 HttpOnly Cookie 里的 refresh token 换新的 access token。
     * refresh token 每次都会轮换（旧令牌立刻失效，60 秒宽限用于多标签页并发）。
     */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(401, "缺少刷新凭证，请重新登录");
        }

        RefreshTokenService.RotateResult rotated = refreshTokenService.rotate(refreshToken);
        if (rotated == null) {
            clearRefreshCookie(response);
            throw new BusinessException(401, "登录已过期，请重新登录");
        }

        LoginResponse session = authService.issueSession(rotated.userId());
        writeRefreshCookie(response, rotated.newToken(), rotated.ttl());
        if (!rotated.reused()) {
            log.debug("refresh token 已轮换，userId={}", rotated.userId());
        }
        return ApiResponse.success(session);
    }

    /**
     * 本设备登出：删除 refresh token、清 Cookie。
     * access token 保留到自然过期（最长 15 分钟）；如需立即作废，打开 jti 黑名单开关即可。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken != null) {
            refreshTokenService.revoke(refreshToken);
        }
        clearRefreshCookie(response);
        blacklistCurrentAccessToken(request);
        return ApiResponse.success();
    }

    /**
     * 全端下线：递增令牌版本号 + 删除该用户全部 refresh token。
     * 此后该用户所有在途 access token 立即失效（受 30 秒本地缓存上限影响）。
     */
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(HttpServletResponse response) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        tokenVersionService.bump(currentUser.userId());
        int revoked = refreshTokenService.revokeAll(currentUser.userId());
        clearRefreshCookie(response);
        log.info("用户全端下线，userId={}，已撤销 refresh token 数={}",
                currentUser.userId(), revoked);
        return ApiResponse.success();
    }

    /** 若请求带了合法 access token，则把它记入 jti 黑名单（开关关闭时也写入，便于随时启用）。 */
    private void blacklistCurrentAccessToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return;
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            return;
        }
        try {
            jwtRevocationService.revoke(token);
        } catch (RuntimeException e) {
            // 令牌已过期或格式非法时无需拉黑，登出本身不应该失败
            log.debug("登出时跳过 access token 拉黑，原因={}", e.getMessage());
        }
    }

    private void writeRefreshCookie(HttpServletResponse response, String token, Duration ttl) {
        ResponseCookie cookie = ResponseCookie
                .from(jwtProperties.getRefreshCookieName(), token)
                .httpOnly(true)
                .secure(jwtProperties.isCookieSecure())
                .sameSite(jwtProperties.getCookieSameSite())
                .path("/api/auth")
                .maxAge(ttl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie
                .from(jwtProperties.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(jwtProperties.isCookieSecure())
                .sameSite(jwtProperties.getCookieSameSite())
                .path("/api/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return java.util.Arrays.stream(cookies)
                .filter(cookie -> jwtProperties.getRefreshCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
