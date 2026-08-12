package com.videonest.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
