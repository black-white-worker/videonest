package com.example.demo.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hot-rank")
public class HotRankProperties {

    private int windowHours = 24;

    private double halfLifeHours = 6D;

    private int maxSize = 50;

    private long currentTtlSeconds = 180;

    private long cardsTtlSeconds = 60;

    private long refreshLockSeconds = 40;
}
