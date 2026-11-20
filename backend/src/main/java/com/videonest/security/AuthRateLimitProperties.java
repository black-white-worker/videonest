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

    /**
     * 续期接口阈值：refresh 是唯一“用凭证换凭证”的接口，必须纳入限流。
     * 正常客户端 15 分钟才续期一次，阈值给宽一些以兼容同一出口 IP 下的多人（校园网/公司 NAT），
     * 但仍然挡得住暴力轮换与凭证探测。
     */
    private int refreshMaxRequests = 120;
    private int refreshWindowSeconds = 60;
}
