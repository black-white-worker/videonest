package com.example.demo.module.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.module.notification.entity.Notification;
import com.example.demo.module.notification.event.NotificationEvent;
import com.example.demo.module.notification.mapper.NotificationMapper;
import com.example.demo.module.notification.service.NotificationMessageConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationMessageConsumerServiceImpl
        implements NotificationMessageConsumerService {

    private final ObjectMapper objectMapper;
    private final NotificationMapper notificationMapper;

    public NotificationMessageConsumerServiceImpl(
            ObjectMapper objectMapper,
            NotificationMapper notificationMapper
    ) {
        this.objectMapper = objectMapper;
        this.notificationMapper = notificationMapper;
    }

    @Override
    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_QUEUE)
    public void consume(String message) {
        NotificationEvent event = readEvent(message);

        if (event.recipientId().equals(event.actorId())
                && !"REVIEW_TIMEOUT".equals(event.type())
                && !"VIDEO_REJECTED".equals(event.type())) {
            log.debug("忽略自己触发给自己的通知，eventId={}", event.eventId());
            return;
        }

        Long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getEventId, event.eventId())
        );

        if (count > 0) {
            log.info("通知消息已消费，跳过重复投递，eventId={}", event.eventId());
            return;
        }

        Notification notification = new Notification();
        notification.setEventId(event.eventId());
        notification.setRecipientId(event.recipientId());
        notification.setActorId(event.actorId());
        notification.setType(event.type());
        notification.setVideoId(event.videoId());
        notification.setCommentId(event.commentId());
        notification.setContent(event.content());
        notification.setIsRead(0);

        try {
            notificationMapper.insert(notification);
            log.info(
                    "通知消息消费成功，eventId={}，type={}，recipientId={}",
                    event.eventId(),
                    event.type(),
                    event.recipientId()
            );
        } catch (DuplicateKeyException e) {
            log.info("通知唯一键冲突，按幂等消费成功处理，eventId={}", event.eventId());
        }
    }

    private NotificationEvent readEvent(String message) {
        try {
            return objectMapper.readValue(message, NotificationEvent.class);
        } catch (JsonProcessingException e) {
            log.error("通知消息格式错误，payload={}", message, e);
            throw new MessageConversionException("通知消息格式错误", e);
        }
    }
}
