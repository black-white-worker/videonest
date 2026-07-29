package com.example.demo.infrastructure.mq;

import com.example.demo.module.video.event.ResourcePurgeEvent;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoResourceCleanupService;
import com.example.demo.infrastructure.mq.service.DeadLetterRecordService;
import com.example.demo.module.notification.event.NotificationEvent;
import com.example.demo.module.video.event.ReviewTimeoutEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DeadLetterMessageConsumer {

    private final ObjectMapper objectMapper;
    private final VideoMapper videoMapper;
    private final VideoResourceCleanupService cleanupService;
    private final DeadLetterRecordService recordService;

    public DeadLetterMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            VideoResourceCleanupService cleanupService,
            DeadLetterRecordService recordService
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.cleanupService = cleanupService;
        this.recordService = recordService;
    }

    @RabbitListener(queues = RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE)
    public void consumeVideoProcessDeadLetter(String message) {
        try {
            VideoProcessEvent event = objectMapper.readValue(
                    message,
                    VideoProcessEvent.class
            );
            videoMapper.markProcessFailed(
                    event.videoId(),
                    "视频处理重试耗尽，消息已进入死信队列"
            );
            recordService.record(
                    RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE,
                    "VIDEO_PROCESS",
                    String.valueOf(event.videoId()),
                    message,
                    "视频处理重试耗尽"
            );
            log.error("视频处理消息进入死信队列，videoId={}，payload={}", event.videoId(), message);
        } catch (JsonProcessingException e) {
            recordService.record(
                    RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE,
                    "VIDEO_PROCESS",
                    null,
                    message,
                    "消息格式错误，无法提取视频ID"
            );
            log.error("无法解析视频处理死信，payload={}", message, e);
        }
    }

    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_DEAD_LETTER_QUEUE)
    public void consumeNotificationDeadLetter(String message) {
        String businessId = null;
        try {
            businessId = objectMapper.readValue(
                    message,
                    NotificationEvent.class
            ).eventId();
        } catch (JsonProcessingException e) {
            log.error("无法解析通知死信，payload={}", message, e);
        }
        recordService.record(
                RabbitMqConfig.NOTIFICATION_DEAD_LETTER_QUEUE,
                "NOTIFICATION",
                businessId,
                message,
                "通知消息消费重试耗尽"
        );
        log.error("通知消息进入死信队列，需要人工检查，payload={}", message);
    }

    @RabbitListener(queues = RabbitMqConfig.REVIEW_TIMEOUT_DEAD_LETTER_QUEUE)
    public void consumeReviewTimeoutDeadLetter(String message) {
        String businessId = null;
        try {
            businessId = String.valueOf(objectMapper.readValue(
                    message,
                    ReviewTimeoutEvent.class
            ).videoId());
        } catch (JsonProcessingException e) {
            log.error("无法解析审核超时死信，payload={}", message, e);
        }
        recordService.record(
                RabbitMqConfig.REVIEW_TIMEOUT_DEAD_LETTER_QUEUE,
                "REVIEW_TIMEOUT",
                businessId,
                message,
                "审核超时消息消费重试耗尽"
        );
        log.error("审核超时消息进入死信队列，需要人工检查，payload={}", message);
    }

    @RabbitListener(queues = RabbitMqConfig.RESOURCE_PURGE_DEAD_LETTER_QUEUE)
    public void consumeResourcePurgeDeadLetter(String message) {
        try {
            ResourcePurgeEvent event = objectMapper.readValue(
                    message,
                    ResourcePurgeEvent.class
            );
            cleanupService.recordPurgeFailure(
                    event.videoId(),
                    "资源清理重试耗尽，消息已进入死信队列"
            );
            recordService.record(
                    RabbitMqConfig.RESOURCE_PURGE_DEAD_LETTER_QUEUE,
                    "RESOURCE_PURGE",
                    String.valueOf(event.videoId()),
                    message,
                    "资源清理重试耗尽"
            );
            log.error("资源清理消息进入死信队列，videoId={}，payload={}", event.videoId(), message);
        } catch (JsonProcessingException e) {
            recordService.record(
                    RabbitMqConfig.RESOURCE_PURGE_DEAD_LETTER_QUEUE,
                    "RESOURCE_PURGE",
                    null,
                    message,
                    "消息格式错误，无法提取视频ID"
            );
            log.error("无法解析资源清理死信，payload={}", message, e);
        }
    }
}
