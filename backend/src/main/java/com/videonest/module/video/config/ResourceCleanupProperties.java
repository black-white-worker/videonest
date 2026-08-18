package com.videonest.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存放"资源清理"相关配置参数
 * */
@Data
@Component
@ConfigurationProperties(prefix = "resource-cleanup")
public class ResourceCleanupProperties {

    // 资源保留天数
    private int retentionDays = 7;

    // 定时清理任务的执行间隔
    private long fixedDelayMilliseconds = 3_600_000L;

    // 单次清理的条数
    private int batchSize = 20;
}
