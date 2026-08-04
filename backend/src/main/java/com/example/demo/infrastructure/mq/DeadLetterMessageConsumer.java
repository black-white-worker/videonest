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

/**
 * RabbitMQ 死信队列消费者
 * 作用：监听各个业务队列转发过来的死信消息，消息重试多次失败后转入死信队列，由该类统一处理、持久化记录、业务状态标记
 */
/**
 * 尝试反序列化为对应事件并提取业务标识；视频、资源类事件解析成功后需更新数据库任务失败状态，通知类事件无需修改业务数据；
 * 两者无论解析成功或失败均持久化死信消息，并捕获 JSON 解析异常兜底，避免脏消息阻塞消费。
 * */

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

    /**
     * 监听【视频处理死信队列】
     * @ RabbitListener：声明当前方法消费指定队列消息；消息到达后自动执行该方法
     * @param message MQ投递过来的原始JSON字符串消息
     */
    @RabbitListener(queues = RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE)
    public void consumeVideoProcessDeadLetter(String message) {
        try {
            // 将JSON字符串反序列化为视频处理事件实体
            VideoProcessEvent event = objectMapper.readValue(
                    message,
                    VideoProcessEvent.class
            );
            // 更新数据库：标记该视频处理失败，原因是重试耗尽进入死信
            videoMapper.markProcessFailed(
                    event.videoId(),
                    "视频处理重试耗尽，消息已进入死信队列"
            );
            // 将这条死信消息持久化存入死信记录表，用于运维排查
            recordService.record(
                    RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE,
                    "VIDEO_PROCESS",
                    String.valueOf(event.videoId()),        // 业务唯一ID（视频id）
                    message,
                    "视频处理重试耗尽"
            );
            log.error("视频处理消息进入死信队列，videoId={}，payload={}", event.videoId(), message);
        } catch (JsonProcessingException e) {
            // JSON解析异常：消息格式非法、残缺、不是预期事件结构
            recordService.record(
                    RabbitMqConfig.VIDEO_PROCESS_DEAD_LETTER_QUEUE,
                    "VIDEO_PROCESS",
                    null,               // 无法解析业务ID，填null
                    message,
                    "消息格式错误，无法提取视频ID"
            );
            log.error("无法解析视频处理死信，payload={}", message, e);
        }
    }

    /**
     * 监听【通知消息死信队列】
     * @param message 原始通知事件JSON字符串
     */
    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_DEAD_LETTER_QUEUE)
    public void consumeNotificationDeadLetter(String message) {
        // 先初始化业务ID为null，解析失败时保持null存入数据库
        String businessId = null;
        try {
            businessId = objectMapper.readValue(
                    message,
                    NotificationEvent.class
            ).eventId();
        } catch (JsonProcessingException e) {
            // 解析失败只打日志，不中断后续记录逻辑
            log.error("无法解析通知死信，payload={}", message, e);
        }
        // 将这条死信消息持久化存入死信记录表，用于运维排查
        recordService.record(
                RabbitMqConfig.NOTIFICATION_DEAD_LETTER_QUEUE,
                "NOTIFICATION",
                businessId,
                message,
                "通知消息消费重试耗尽"
        );
        log.error("通知消息进入死信队列，需要人工检查，payload={}", message);
    }

    /**
     * 监听【审核超时死信队列】
     * @param message 审核超时事件JSON字符串
     */
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

    /**
     * 监听【资源清理死信队列】
     * 资源清理：删除视频对应的文件资源，多次失败后进入死信
     * @param message 资源清理事件JSON字符串
     */
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
