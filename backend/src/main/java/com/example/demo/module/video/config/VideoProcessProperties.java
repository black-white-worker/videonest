package com.example.demo.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "video-process")
public class VideoProcessProperties {

    private String ffmpegPath = "ffmpeg";

    private long timeoutSeconds = 1800;
}
