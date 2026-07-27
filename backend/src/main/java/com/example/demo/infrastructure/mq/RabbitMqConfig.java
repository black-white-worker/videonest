package com.example.demo.infrastructure.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String NOTIFICATION_EXCHANGE =
            "videonest.notification.exchange";

    public static final String NOTIFICATION_QUEUE =
            "videonest.notification.queue";

    public static final String NOTIFICATION_ROUTING_KEY =
            "videonest.notification";

    public static final String VIDEO_PROCESS_EXCHANGE =
            "videonest.video.process.exchange";

    public static final String VIDEO_PROCESS_QUEUE =
            "videonest.video.process.queue";

    public static final String VIDEO_PROCESS_ROUTING_KEY =
            "videonest.video.process";

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(NOTIFICATION_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationQueue() {
        return new Queue(NOTIFICATION_QUEUE, true);
    }

    @Bean
    public Binding notificationBinding(
            Queue notificationQueue,
            DirectExchange notificationExchange
    ) {
        return BindingBuilder
                .bind(notificationQueue)
                .to(notificationExchange)
                .with(NOTIFICATION_ROUTING_KEY);
    }

    @Bean
    public DirectExchange videoProcessExchange() {
        return new DirectExchange(VIDEO_PROCESS_EXCHANGE, true, false);
    }

    @Bean
    public Queue videoProcessQueue() {
        return new Queue(VIDEO_PROCESS_QUEUE, true);
    }

    @Bean
    public Binding videoProcessBinding(
            Queue videoProcessQueue,
            DirectExchange videoProcessExchange
    ) {
        return BindingBuilder.bind(videoProcessQueue)
                .to(videoProcessExchange)
                .with(VIDEO_PROCESS_ROUTING_KEY);
    }
}