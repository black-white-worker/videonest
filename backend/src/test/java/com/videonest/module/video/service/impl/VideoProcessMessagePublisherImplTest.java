package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import com.videonest.module.video.event.VideoProcessEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VideoProcessMessagePublisherImplTest {

    @Test
    void springEventIsPersistedToVideoProcessOutbox() {
        TransactionalOutboxService outboxService = mock(TransactionalOutboxService.class);
        VideoProcessEvent event = new VideoProcessEvent(42L, "video/7/source.mp4");

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    TransactionalOutboxService.class,
                    () -> outboxService
            );
            context.registerBean(VideoProcessMessagePublisherImpl.class);
            context.refresh();

            context.publishEvent(event);
        }

        verify(outboxService).append(
                isNull(),
                eq("VIDEO_PROCESS"),
                eq(RabbitMqConfig.VIDEO_PROCESS_EXCHANGE),
                eq(RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY),
                eq(event)
        );
    }
}
