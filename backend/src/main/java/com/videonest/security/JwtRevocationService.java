package com.videonest.security;

import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * JWT 撤销服务：解决 JWT "一旦签发、过期前无法作废"的天生缺陷。
 * 所以用户"登出"时不能像 Session 那样直接销毁
 *   1. 签发 JWT 时在载荷里放一个唯一 jti（JWT ID）；
 *   2. 用户登出时，把这个 jti 作为 key 写入 Redis，并设置 TTL = 令牌剩余有效期；
 *   3. 每次请求校验 JWT 时，查 Redis 里有没有这个 jti，有就说明已撤销，拒绝访问。
 * 这样既保留了 JWT 无状态的优势，又实现了主动作废。
 */
@Service
public class JwtRevocationService {

    private static final String KEY_PREFIX = "videonest:jwt:revoked:";

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public JwtRevocationService(
            StringRedisTemplate redisTemplate,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
    }


    /**
     * 撤销令牌（用户登出时调用）。
     * 把当前 Token 的 jti 写入 Redis，标记为"已作废"。
     * @param token 原始 JWT 字符串
     */
    public void revoke(String token) {
        Claims claims = jwtTokenProvider.parseToken(token);
        String tokenId = requireTokenId(claims);
        redisTemplate.opsForValue().set(
                KEY_PREFIX + tokenId,
                "1",
                //令牌剩余有效期（秒）—— 过期后 Redis 自动删除，避免数据无限堆积
                jwtTokenProvider.getRemainingTtlSeconds(claims),
                TimeUnit.SECONDS        // 设置单位
        );
    }

    /**
     * 检查令牌是否已被撤销（每次请求校验 JWT 时调用）。
     * @param claims 已解析的 JWT 载荷
     * @return true=已撤销（应拒绝），false=有效
     */
    public boolean isRevoked(Claims claims) {
        String tokenId = requireTokenId(claims);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException e) {
            throw new JwtRevocationCheckException("无法校验令牌撤销状态", e);
        }
    }

    /**
     * 私有工具方法：从 Claims 中安全获取 jti（令牌ID）。
     * @param claims JWT 载荷
     * @return 非空的 jti 字符串
     */
    private String requireTokenId(Claims claims) {
        String tokenId = claims.getId();
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalArgumentException("Token 缺少 jti");
        }
        return tokenId;
    }
}
