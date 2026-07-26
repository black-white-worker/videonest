package com.example.demo.module.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.module.notification.entity.Notification;
import com.example.demo.module.notification.event.NotificationEvent;
import com.example.demo.module.notification.mapper.NotificationMapper;
import com.example.demo.module.notification.service.NotificationMessageConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
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
    public void consume(String message) throws Exception {
        NotificationEvent event = objectMapper.readValue(
                message,
                NotificationEvent.class
        );

        if (event.recipientId().equals(event.actorId())) {
            return;
        }

        Long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getEventId, event.eventId())
        );

        if (count > 0) {
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

        notificationMapper.insert(notification);
    }
}