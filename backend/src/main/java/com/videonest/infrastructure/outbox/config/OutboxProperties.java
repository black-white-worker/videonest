package com.videonest.infrastructure.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox 配置模型。实际默认值与 application.yml 及调度器中的原值一致。
 */
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    private boolean enabled = true;
    private long dispatchIntervalMilliseconds = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDispatchIntervalMilliseconds() {
        return dispatchIntervalMilliseconds;
    }

    public void setDispatchIntervalMilliseconds(long dispatchIntervalMilliseconds) {
        this.dispatchIntervalMilliseconds = dispatchIntervalMilliseconds;
    }
}
