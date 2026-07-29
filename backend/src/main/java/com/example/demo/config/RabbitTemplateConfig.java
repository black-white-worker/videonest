package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitTemplateConfig {

    private final RabbitTemplate rabbitTemplate;

    public RabbitTemplateConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void configureCallbacks() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, acknowledged, cause) -> {
            String messageId = correlationData == null ? null : correlationData.getId();
            if (acknowledged) {
                log.debug("RabbitMQ 消息已被 Broker 确认，messageId={}", messageId);
                return;
            }
            log.error("RabbitMQ 消息未被 Broker 确认，messageId={}，cause={}", messageId, cause);
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "RabbitMQ 消息无法路由，exchange={}，routingKey={}，replyCode={}，replyText={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText()
        ));
    }
}
