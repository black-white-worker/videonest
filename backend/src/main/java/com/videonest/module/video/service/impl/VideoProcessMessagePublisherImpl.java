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
     * 监听视频创建事务中发布的处理事件，并在当前事务内写入 Outbox。
     *
     * <p>Spring 的默认事件监听是同步的，这里只做一次数据库写入；
     * 真正的 RabbitMQ 投递和 FFmpeg 转码由后台调度器与 MQ 消费者异步执行。</p>
     *
     * @param event 视频处理事件对象，携带视频业务参数
     */
    @EventListener
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
