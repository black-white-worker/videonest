package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.common.exception.MessagePublishException;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.service.VideoProcessMessagePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Service
@Slf4j
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
        String messageId = UUID.randomUUID().toString();
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.VIDEO_PROCESS_EXCHANGE,
                    RabbitMqConfig.VIDEO_PROCESS_ROUTING_KEY,
                    objectMapper.writeValueAsString(event),
                    message -> {
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    },
                    new CorrelationData(messageId)
            );
            log.info(
                    "视频转码消息发送成功，videoId={}，messageId={}",
                    event.videoId(),
                    messageId
            );
        } catch (JsonProcessingException e) {
            throw new MessagePublishException(
                    "VIDEO_PROCESS",
                    "视频转码消息序列化失败",
                    e
            );
        } catch (AmqpException e) {
            throw new MessagePublishException(
                    "VIDEO_PROCESS",
                    "视频转码消息发送失败",
                    e
            );
        }
    }
}
