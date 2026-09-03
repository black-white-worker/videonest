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

    /**
     * 转码锁的初始 TTL。看门狗会在任务运行期间自动续期；进程异常退出后，
     * 该 TTL 仍是允许其他消费者接管任务的最长等待时间。
     */
    private long lockLeaseSeconds = 300;

    /**
     * 转码超时兜底阈值（分钟）。视频处于 PROCESSING 状态超过该时长仍未更新，
     * 兜底扫描任务会重新投递转码消息。需大于最大可能转码耗时
     * （单次 FFmpeg 超时 30 分钟 × 3 个分辨率 + 下载上传时间），避免误判。
     */
    private long processingTimeoutMinutes = 120;

    /**
     * 兜底扫描每批最多处理的视频条数，防止一次性投递过多消息压垮消费者。
     */
    private int stuckScanBatchSize = 20;
}
