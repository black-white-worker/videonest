package com.example.demo.infrastructure.mq;

import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.event.NotificationEvent;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.event.ResourcePurgeEvent;
import com.example.demo.module.video.event.ReviewTimeoutEvent;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoResourceCleanupService;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
public class DelayedMessageConsumer {

    private final ObjectMapper objectMapper;
    private final VideoMapper videoMapper;
    private final VideoResourceCleanupService cleanupService;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisTemplate<String, Object> redisTemplate;

    public DelayedMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            VideoResourceCleanupService cleanupService,
            ApplicationEventPublisher eventPublisher,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.cleanupService = cleanupService;
        this.eventPublisher = eventPublisher;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    @RabbitListener(queues = RabbitMqConfig.REVIEW_TIMEOUT_QUEUE)
    public void consumeReviewTimeout(String message) {
        ReviewTimeoutEvent event = readEvent(
                message,
                ReviewTimeoutEvent.class,
                "审核超时"
        );
        int rows = videoMapper.markReviewTimedOut(event.videoId());
        if (rows == 0) {
            log.info("视频已审核、已删除或已处理过超时通知，videoId={}", event.videoId());
            return;
        }

        Video video = videoMapper.selectById(event.videoId());
        if (video != null) {
            eventPublisher.publishEvent(
                    new NotificationDomainEvent(
                            new NotificationEvent(
                                    UUID.randomUUID().toString(),
                                    video.getAuthorId(),
                                    video.getAuthorId(),
                                    "REVIEW_TIMEOUT",
                                    video.getId(),
                                    null,
                                    "你的视频等待审核已超时，管理员会尽快处理"
                            )
                    )
            );
        }
        redisTemplate.opsForValue().increment(RedisKeys.REVIEW_TIMEOUT_COUNT);
        log.warn("视频审核超时，videoId={}", event.videoId());
    }

    @RabbitListener(queues = RabbitMqConfig.RESOURCE_PURGE_QUEUE)
    public void consumeResourcePurge(String message) {
        ResourcePurgeEvent event = readEvent(
                message,
                ResourcePurgeEvent.class,
                "资源清理"
        );
        cleanupService.purgeVideo(event.videoId());
    }

    private <T> T readEvent(String message, Class<T> eventType, String messageType) {
        try {
            return objectMapper.readValue(message, eventType);
        } catch (JsonProcessingException e) {
            log.error("{}消息格式错误，payload={}", messageType, message, e);
            throw new MessageConversionException(messageType + "消息格式错误", e);
        }
    }
}
