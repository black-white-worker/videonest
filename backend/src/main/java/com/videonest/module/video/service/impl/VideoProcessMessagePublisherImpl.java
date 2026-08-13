package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.service.VideoProcessMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VideoProcessMessagePublisherImpl
        implements VideoProcessMessagePublisher {

    private final TransactionalOutboxService outboxService;

    public VideoProcessMessagePublisherImpl(TransactionalOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Override
    @EventListener
    public void publish(VideoProcessEvent event) {
        outboxService.append(
                null,
                "VIDEO_PROCESS",
                RabbitMqConfig.VIDEO_PROCESS_EXCHANGE,
                RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY,
                event
        );
        log.info("视频转码事件已写入 Outbox，videoId={}", event.videoId());
    }
}
