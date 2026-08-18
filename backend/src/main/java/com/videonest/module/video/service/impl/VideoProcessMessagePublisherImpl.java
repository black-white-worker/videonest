package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.service.VideoProcessMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 实现方案：Outbox事务消息模式，不是直接发RabbitMQ
 * 把消息写入outbox数据库表，而不是直接调用MQ发送API
 * 优势：保证业务数据库操作和消息投递原子性；避免业务库成功，MQ发送失败导致消息丢失
 */
@Service
@Slf4j
public class VideoProcessMessagePublisherImpl
        implements VideoProcessMessagePublisher {

    private final TransactionalOutboxService outboxService;

    public VideoProcessMessagePublisherImpl(TransactionalOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * 发布视频处理事件
     * @param event 视频处理事件对象，携带视频业务参数
     */
    @Override
    public void publish(VideoProcessEvent event) {
        // 向outbox消息表插入一条待发送消息
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
