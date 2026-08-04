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

/**
 * 延迟消息消费者
 * 监听延迟队列：审核超时消息、视频资源清理消息
 */
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

    /**
     * 消费【视频审核超时】延迟消息
     * @param message MQ接收的原始JSON字符串
     */
    @Transactional
    @RabbitListener(queues = RabbitMqConfig.REVIEW_TIMEOUT_QUEUE)
    public void consumeReviewTimeout(String message) {
        //封装好的反序列化
        ReviewTimeoutEvent event = readEvent(
                message,
                ReviewTimeoutEvent.class,
                "审核超时"
        );
        // 更新数据库：标记视频审核超时，返回影响行数
        int rows = videoMapper.markReviewTimedOut(event.videoId());
        // 防止重复执行
        if (rows == 0) {
            log.info("视频已审核、已删除或已处理过超时通知，videoId={}", event.videoId());
            return;
        }

        Video video = videoMapper.selectById(event.videoId());

        // 视频存在，推送通知给作者
        if (video != null) {
            // 发布Spring本地事件，解耦通知发送逻辑
            eventPublisher.publishEvent(
                    new NotificationDomainEvent(
                            // 组装通知事件
                            new NotificationEvent(
                                    UUID.randomUUID().toString(),       // 通知唯一ID
                                    video.getAuthorId(),                // 接收人
                                    video.getAuthorId(),                // 发送人（临时用作者代替）
                                    "REVIEW_TIMEOUT",                   // 通知类型
                                    video.getId(),                      // 关联视频id
                                    null,
                                    "你的视频等待审核已超时，管理员会尽快处理"  // 通知内容
                            )
                    )
            );
        }
        // Redis计数器自增，统计审核超时视频总数，用于监控
        redisTemplate.opsForValue().increment(RedisKeys.REVIEW_TIMEOUT_COUNT);
        log.warn("视频审核超时，videoId={}", event.videoId());
    }

    /**
     * 消费【视频资源清理】延迟消息
     * @param message MQ原始JSON字符串
     */
    @RabbitListener(queues = RabbitMqConfig.RESOURCE_PURGE_QUEUE)
    public void consumeResourcePurge(String message) {
        ResourcePurgeEvent event = readEvent(
                message,
                ResourcePurgeEvent.class,
                "资源清理"
        );
        // 执行清理：删除视频对应的存储文件
        cleanupService.purgeVideo(event.videoId());
    }

    /**
     * 通用消息解析泛型方法
     * 统一处理JSON反序列化，消除重复try-catch代码
     * @param message MQ原始字符串
     * @param eventType 目标事件Class
     * @param messageType 消息名称，用于日志输出
     * @return 解析完成的事件对象
     * @throws MessageConversionException JSON格式错误时抛出
     */
    private <T> T readEvent(String message, Class<T> eventType, String messageType) {
        try {
            return objectMapper.readValue(message, eventType);
        } catch (JsonProcessingException e) {
            log.error("{}消息格式错误，payload={}", messageType, message, e);
            throw new MessageConversionException(messageType + "消息格式错误", e);
        }
    }
}
