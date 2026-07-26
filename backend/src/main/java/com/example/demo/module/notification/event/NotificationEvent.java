package com.example.demo.module.notification.event;

public record NotificationEvent(
        String eventId,
        Long recipientId,
        Long actorId,
        String type,
        Long videoId,
        Long commentId,
        String content
) {
}