package com.example.demo.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    @NotBlank(message = "JWT 密钥不能为空")
    @Size(min = 32, message = "JWT 密钥至少需要 32 个字符")
    private String secret;

    private long expireMinutes = 1440;
}
