package com.videonest.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JWT令牌提供者
 * 作用：负责生成JWT令牌、解析JWT令牌、计算令牌剩余存活时间
 */
@Component
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;

        // 把配置里的密钥字符串按UTF-8编码转成字节数组
        // 因为Keys.hmacShaKeyFor()只接收字节数组，不接收字符串
        byte[] keyBytes = jwtProperties.getSecret()
                .getBytes(StandardCharsets.UTF_8);

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 创建JWT令牌
     * @param user 用户信息（用户ID、用户名、角色）
     * @return 生成的JWT字符串
     */
    public String createToken(SysUserTokenInfo user) {
        Instant now = Instant.now();

        // 过期时间
        Instant expiryTime = now.plus(
                jwtProperties.getExpireMinutes(),
                ChronoUnit.MINUTES
        );

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.username())
                // 自定义载荷字段：存入用户ID
                // claim就是往Payload里塞自定义键值对
                .claim("userId", user.userId())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiryTime))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析JWT令牌
     * @param token 前端传过来的JWT字符串
     * @return 解析后的载荷对象Claims（里面有userId、role、过期时间等）
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();      // 只取出载荷部分（Claims），丢弃Header和签名
    }


    /**
     * 获取令牌剩余存活时间（秒）
     * @param claims 已解析的载荷对象（里面有过期时间）
     * @return 剩余秒数，最少返回1秒（防止出现0或负数）
     * 典型用途：刷新Redis中token的过期时间，保持会话活跃
     */
    public long getRemainingTtlSeconds(Claims claims) {
        long remainingMillis = claims.getExpiration().getTime()
                - System.currentTimeMillis();
        return Math.max(remainingMillis / 1000, 1);     // 毫秒转秒
    }

    /**
     * 用户令牌信息记录类
     * 自动生成构造器、getter、equals、hashCode、toString
     * 作用：作为createToken方法的入参，封装生成令牌需要的3个字段
     */
    public record SysUserTokenInfo(
            Long userId,
            String username,
            String role
    ) {
    }
}
