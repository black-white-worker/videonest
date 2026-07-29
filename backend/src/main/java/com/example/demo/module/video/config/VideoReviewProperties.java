package com.example.demo.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "video-review")
public class VideoReviewProperties {

    private long timeoutMilliseconds = 86_400_000L;
}
