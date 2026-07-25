package com.example.demo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;

        byte[] keyBytes = jwtProperties.getSecret()
                .getBytes(StandardCharsets.UTF_8);

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String createToken(SysUserTokenInfo user) {
        Instant now = Instant.now();

        Instant expiryTime = now.plus(
                jwtProperties.getExpireMinutes(),
                ChronoUnit.MINUTES
        );

        return Jwts.builder()
                .subject(user.username())
                .claim("userId", user.userId())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiryTime))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record SysUserTokenInfo(
            Long userId,
            String username,
            String role
    ) {
    }
}