package com.videonest.module.video.service;

import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.infrastructure.redis.RenewableRedisLock;
import com.videonest.module.video.config.VideoProcessProperties;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 转码超时兜底扫描器。
 *
 * <p>正常情况下，消费者拿到可续期锁后由看门狗自动续期，转码多久都不会丢锁。
 * 但如果消费者进程在持有锁期间直接崩溃（OOM、kill -9、机器断电），
 * RabbitMQ 会把未 ack 的消息重新入队，而新消费者抢锁时可能因为旧锁还没过期
 * （最多 300 秒）而抢锁失败、直接 ack 丢弃消息，导致视频永久卡在 PROCESSING 状态。</p>
 *
 * <p>本类定时扫描长时间处于 PROCESSING 且 update_time 不再变化的视频，
 * 重新投递转码消息。此时旧锁早已过期，新消费者可以正常抢锁并处理。</p>
 *
 * <p>扫描任务本身也加分布式锁，多实例部署时保证只有一台执行，避免重复投递。</p>
 */
@Slf4j
@Component
public class VideoProcessRecoveryScheduler {

    // 扫描任务锁租约：5 分钟足够覆盖一次扫描（一次查询+批量投递），实例崩溃后由 Redis 自动释放
    private static final long JOB_LOCK_MINUTES = 5;

    private final VideoMapper videoMapper;
    private final VideoProcessMessagePublisher messagePublisher;
    private final VideoProcessProperties properties;
    private final RenewableRedisLock renewableRedisLock;

    public VideoProcessRecoveryScheduler(
            VideoMapper videoMapper,
            VideoProcessMessagePublisher messagePublisher,
            VideoProcessProperties properties,
            RenewableRedisLock renewableRedisLock
    ) {
        this.videoMapper = videoMapper;
        this.messagePublisher = messagePublisher;
        this.properties = properties;
        this.renewableRedisLock = renewableRedisLock;
    }

    /**
     * 定时扫描卡住的转码任务并重新投递消息。
     * initialDelay：启动后延迟 60 秒再跑第一次，给正常启动留缓冲；
     * fixedDelay：上一轮跑完后间隔 5 分钟再跑下一轮。
     */
    @Scheduled(
            initialDelayString = "${video-process.stuck-scan-initial-delay-milliseconds:60000}",
            fixedDelayString = "${video-process.stuck-scan-fixed-delay-milliseconds:300000}"
    )
    public void scanStuckProcessingVideos() {
        // 多实例部署时，只有一台能拿到扫描锁，避免重复投递消息
        Optional<RenewableRedisLock.LockHandle> acquiredLock;
        try {
            acquiredLock = renewableRedisLock.tryAcquire(
                    RedisKeys.VIDEO_PROCESS_RECOVERY_JOB_LOCK,
                    JOB_LOCK_MINUTES,
                    TimeUnit.MINUTES
            );
        } catch (RuntimeException e) {
            log.warn("获取转码超时扫描锁失败，本轮跳过", e);
            return;
        }
        if (acquiredLock.isEmpty()) {
            return;
        }

        try (RenewableRedisLock.LockHandle ignored = acquiredLock.get()) {
            doScan();
        }
    }

    /** 定时扫描 */
    private void doScan() {
        // 计算超时阈值：当前时间减去配置的超时分钟数
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(
                properties.getProcessingTimeoutMinutes()
        );

        List<Long> stuckIds;
        try {
            stuckIds = videoMapper.selectStuckProcessingVideoIds(
                    threshold,
                    properties.getStuckScanBatchSize()
            );
        } catch (RuntimeException e) {
            log.warn("查询转码超时视频失败，本轮跳过", e);
            return;
        }

        if (stuckIds == null || stuckIds.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (Long videoId : stuckIds) {
            try {
                // 重新投递前再查一次状态，避免查询和投递之间视频刚好转码完成
                Video video = videoMapper.selectById(videoId);
                if (video == null || !"PROCESSING".equals(video.getStatus())) {
                    continue;
                }
                // 没有原始源文件就没法转码，跳过并记录
                if (!StringUtils.hasText(video.getOriginalVideoUrl())) {
                    log.warn("转码超时视频缺少原始源文件，跳过重新投递，videoId={}", videoId);
                    continue;
                }
                // 通过 Outbox 重新投递转码消息，消费者那边有锁和状态双重幂等，不会重复转码
                messagePublisher.publish(
                        new VideoProcessEvent(videoId, video.getOriginalVideoUrl())
                );
                recovered++;
                log.info("检测到转码超时视频，已重新投递处理消息，videoId={}", videoId);
            } catch (RuntimeException e) {
                log.warn("重新投递超时转码消息失败，videoId={}", videoId, e);
            }
        }

        if (recovered > 0) {
            log.info("转码超时兜底扫描完成，扫描 {} 条，重新投递 {} 条", stuckIds.size(), recovered);
        }
    }
}
