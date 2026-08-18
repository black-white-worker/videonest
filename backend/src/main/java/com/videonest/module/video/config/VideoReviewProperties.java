package com.videonest.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频审核业务配置属性类
 * 接收视频审核模块相关外部配置参数，给审核业务代码读取使用
 */
@Data
@Component
@ConfigurationProperties(prefix = "video-review")
public class VideoReviewProperties {

    // 审核超时时间
    private long timeoutMilliseconds = 86_400_000L;
}
