package com.videonest.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频处理配置属性类，专门接收ffmpeg相关配置参数
 * 读取yml配置，提供给视频转码、视频元信息读取业务使用
 */
@Data
@Component
@ConfigurationProperties(prefix = "video-process")
public class VideoProcessProperties {

    // 从系统环境变量PATH中寻找ffmpeg命令
    private String ffmpegPath = "ffmpeg";

    // fprobe用来解析视频元数据(分辨率、时长、码率)，默认从环境变量找命令
    private String ffprobePath = "ffprobe";

    private long timeoutSeconds = 1800;
}
