package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.service.VideoProcessMessagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class VideoProcessMessagePublisherImpl
        implements VideoProcessMessagePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public VideoProcessMessagePublisherImpl(
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(VideoProcessEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.VIDEO_PROCESS_EXCHANGE,
                    RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY,
                    objectMapper.writeValueAsString(event)
            );
        } catch (Exception e) {
            throw new IllegalStateException("视频转码消息发送失败", e);
        }
    }
}
