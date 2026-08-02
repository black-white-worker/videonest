package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitTemplate 自定义配置
 * 作用：开启RabbitMQ消息投递确认机制，解决消息丢失问题
 */
/**
 * 消息连交换机都到不了 → ConfirmCallback acknowledged=false
 * 消息到达交换机，但找不到队列 → Confirm 成功 + 触发 ReturnsCallback
 * 消息到达交换机，正常路由队列 → 两个回调都不报错
 * */
@Slf4j
@Configuration
public class RabbitTemplateConfig {

    private final RabbitTemplate rabbitTemplate;

    public RabbitTemplateConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 配置消息确认回调
     * 生产者发消息 → RabbitMQ Broker 收到消息后，主动回一条回执。
     * SpringAMQP 把这条回执封装成回调接口 ConfirmCallback。
     */
    @PostConstruct
    public void configureCallbacks() {
        // 开启 mandatory 机制：消息无法路由到队列时，触发ReturnsCallback回调
        rabbitTemplate.setMandatory(true);
        /**
         * 消息到达Exchange交换机后触发（成功/失败都会回调）
         * correlationData：消息关联数据
         * */
        //消息有或没有成功送到 Broker（交换机）都要回调
        rabbitTemplate.setConfirmCallback((correlationData, acknowledged, cause) -> {
            String messageId = correlationData == null ? null : correlationData.getId();
            if (acknowledged) {
                log.debug("RabbitMQ 消息已被 Broker 确认，messageId={}", messageId);
                return;
            }
            log.error("RabbitMQ 消息未被 Broker 确认，messageId={}，cause={}", messageId, cause);
        });
        //消息退回回调：消息到达交换机了，但是找不到队列，消息被退回
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "RabbitMQ 消息无法路由，exchange={}，routingKey={}，replyCode={}，replyText={}",
                returned.getExchange(),     // 交换机名称
                returned.getRoutingKey(),   // 发送时使用的路由键
                returned.getReplyCode(),    // 响应错误码
                returned.getReplyText()     // 错误描述
        ));
    }
}
