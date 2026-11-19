package com.videonest.security;

/**
 * 读取令牌版本号时 Redis 不可用。
 *
 * <p>由 {@link TokenVersionService} 抛出，供上层决定降级策略：
 * 认证校验走 fail-open，登录等写路径则可以让请求失败。
 */
public class TokenVersionUnavailableException extends RuntimeException {

    private final Long userId;

    public TokenVersionUnavailableException(Long userId, Throwable cause) {
        super("无法读取用户令牌版本号", cause);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
