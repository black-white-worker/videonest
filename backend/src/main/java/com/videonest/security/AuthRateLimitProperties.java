package com.videonest.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证接口限流配置属性类
 * 作用：集中管理登录、注册接口IP限流的所有可配置参数
 * 全部参数提供默认值，配置文件不填写时自动使用默认值，无需强制配置
 * 配合 AuthEndpointRateLimitFilter 限流过滤器完成动态阈值控制
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth-rate-limit")
public class AuthRateLimitProperties {

    private boolean enabled = true;
    private int loginMaxRequests = 20;
    private int loginWindowSeconds = 60;
    private int registerMaxRequests = 5;
    private int registerWindowSeconds = 3600;
}
