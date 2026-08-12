package com.videonest.security;

import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

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

    public void revoke(String token) {
        Claims claims = jwtTokenProvider.parseToken(token);
        String tokenId = requireTokenId(claims);
        redisTemplate.opsForValue().set(
                KEY_PREFIX + tokenId,
                "1",
                jwtTokenProvider.getRemainingTtlSeconds(claims),
                TimeUnit.SECONDS
        );
    }

    public boolean isRevoked(Claims claims) {
        String tokenId = requireTokenId(claims);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RuntimeException e) {
            throw new JwtRevocationCheckException("无法校验令牌撤销状态", e);
        }
    }

    private String requireTokenId(Claims claims) {
        String tokenId = claims.getId();
        if (!StringUtils.hasText(tokenId)) {
            throw new IllegalArgumentException("Token 缺少 jti");
        }
        return tokenId;
    }
}
