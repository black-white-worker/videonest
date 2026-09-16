package com.videonest.module.video.service;

import com.videonest.infrastructure.mq.DelayedMessageConsumer;
import com.videonest.module.video.config.VideoReviewProperties;
import com.videonest.module.video.mapper.VideoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Recovers overdue reviews created before timeout messaging or missed during broker downtime. */
@Service
@Slf4j
public class ReviewTimeoutRecoveryScheduler {
    private final VideoMapper videoMapper;
    private final DelayedMessageConsumer delayedMessageConsumer;
    private final VideoReviewProperties properties;

    public ReviewTimeoutRecoveryScheduler(VideoMapper videoMapper, DelayedMessageConsumer delayedMessageConsumer,
                                          VideoReviewProperties properties) {
        this.videoMapper = videoMapper;
        this.delayedMessageConsumer = delayedMessageConsumer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${video-review.timeout-recovery-fixed-delay-milliseconds:300000}")
    public void recoverMissedTimeouts() {
        for (Long videoId : videoMapper.selectDueReviewTimeoutVideoIds(properties.getTimeoutRecoveryBatchSize())) {
            try {
                delayedMessageConsumer.processReviewTimeout(videoId);
            } catch (RuntimeException error) {
                log.warn("补偿审核超时提醒失败，videoId={}", videoId, error);
            }
        }
    }
}
