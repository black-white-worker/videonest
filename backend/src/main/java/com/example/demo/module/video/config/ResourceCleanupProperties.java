package com.example.demo.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "resource-cleanup")
public class ResourceCleanupProperties {

    private int retentionDays = 7;

    private long fixedDelayMilliseconds = 3_600_000L;

    private int batchSize = 20;
}
