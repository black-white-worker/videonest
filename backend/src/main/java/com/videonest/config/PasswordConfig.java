package com.videonest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器配置
 * 用于用户密码加密、登录时密码比对，配合SpringSecurity使用
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength=8：加密迭代次数为 2^8=256 次，比默认强度 10（2^10=1024 次）
        return new BCryptPasswordEncoder(8);
    }
}
