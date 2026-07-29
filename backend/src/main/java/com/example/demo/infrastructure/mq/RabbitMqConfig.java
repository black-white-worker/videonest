package com.example.demo.infrastructure.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * RabbitMQ配置类
 * 作用：定义2套独立Direct直连交换机+队列+绑定关系
 * 1. 消息通知队列（站内通知、消息推送）
 * 2. 视频处理队列（视频转码、切片任务）
 * 项目启动时，SpringAMQP 自动利用这些对象，
 * 远程在 RabbitMQ 创建交换机、队列、绑定关系
 */

@Configuration
public class RabbitMqConfig {

    /**通知消息常量定义  项目名.模块名.资源名*/
    /**/
    // 通知交换机名称常量
    public static final String NOTIFICATION_EXCHANGE =
            "videonest.notification.exchange";

    // 通知队列名称常量
    public static final String NOTIFICATION_QUEUE =
            "videonest.notification.queue.v2";

    // 通知队列路由key常量
    public static final String NOTIFICATION_ROUTING_KEY =
            "videonest.notification";

    public static final String DEAD_LETTER_EXCHANGE =
            "videonest.dead.letter.exchange";

    public static final String NOTIFICATION_DEAD_LETTER_QUEUE =
            "videonest.notification.dlq";

    public static final String NOTIFICATION_DEAD_LETTER_ROUTING_KEY =
            "videonest.notification.dead";

    /**视频处理任务常量定义*/
    // 视频处理交换机名称
    public static final String VIDEO_PROCESS_EXCHANGE =
            "videonest.video.process.exchange";

    // 视频处理队列名称
    public static final String VIDEO_PROCESS_QUEUE =
            "videonest.video.process.queue.v2";

    // 视频任务路由key
    public static final String VIDEO_PROCESS_ROUTING_KEY =
            "videonest.video.process";

    public static final String VIDEO_PROCESS_DEAD_LETTER_QUEUE =
            "videonest.video.process.dlq";

    public static final String VIDEO_PROCESS_DEAD_LETTER_ROUTING_KEY =
            "videonest.video.process.dead";

    public static final String DELAYED_EXCHANGE =
            "videonest.delayed.exchange";

    public static final String REVIEW_TIMEOUT_QUEUE =
            "videonest.video.review.timeout.queue";

    public static final String REVIEW_TIMEOUT_ROUTING_KEY =
            "videonest.video.review.timeout";

    public static final String REVIEW_TIMEOUT_DEAD_LETTER_QUEUE =
            "videonest.video.review.timeout.dlq";

    public static final String REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY =
            "videonest.video.review.timeout.dead";

    public static final String RESOURCE_PURGE_QUEUE =
            "videonest.resource.purge.queue";

    public static final String RESOURCE_PURGE_ROUTING_KEY =
            "videonest.resource.purge";

    public static final String RESOURCE_PURGE_DEAD_LETTER_QUEUE =
            "videonest.resource.purge.dlq";

    public static final String RESOURCE_PURGE_DEAD_LETTER_ROUTING_KEY =
            "videonest.resource.purge.dead";

    /**
     * 创建通知直连交换机实例
     * DirectExchange构造参数说明：
     * 参数1：交换机名称
     * 参数2：durable = true → 持久化，RabbitMQ重启交换机不删除
     * 参数3：autoDelete = false → 没有消费者连接时，交换机不会自动删除
     */
    /*DirectExchange精确匹配routingKey
    *交换机负责接收生产者消息，按照预先设置好的路由规则（Binding 绑定 + routingKey），把消息分发到一个或多个队列。
    * 生产者消息携带 routingKey，只会转发给绑定这个交换机、并且绑定时写了一模一样 routingKey 的队列
    * */
    @Bean
    public DirectExchange notificationExchange() {
        /*类似DirectExchange notificationExchange = new DirectExchange("videonest.notification.exchange",true,false);*/
        return new DirectExchange(NOTIFICATION_EXCHANGE, true, false);
    }

    /**
     * 创建通知队列Bean
     * Queue构造参数：
     * 参数1：队列名称
     * 参数2：durable=true 队列持久化，重启队列不丢失
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    /**
     * 创建绑定关系Bean：把通知队列绑定到通知交换机
     * 入参：Spring自动注入上面定义好的队列Bean、交换机Bean
     * BindingBuilder：SpringAMQP提供的绑定构建工具
     * bind(队列).to(交换机).with(路由key)
     * 含义：消息发送到交换机，路由key匹配NOTIFICATION_ROUTING_KEY才分发到此队列
     */
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

    /**
     * 创建视频处理直连交换机
     */
    @Bean
    public DirectExchange videoProcessExchange() {
        return new DirectExchange(VIDEO_PROCESS_EXCHANGE, true, false);
    }

    /**
     * 创建视频处理任务队列
     */
    @Bean
    public Queue videoProcessQueue() {
        return QueueBuilder.durable(VIDEO_PROCESS_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(VIDEO_PROCESS_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    /**
     * 视频队列与视频交换机绑定
     * 只有路由key = videonest.video.process 的消息才会进入视频处理队列
     */
    @Bean
    public Binding videoProcessBinding(
            Queue videoProcessQueue,
            DirectExchange videoProcessExchange
    ) {
        return BindingBuilder.bind(videoProcessQueue)
                .to(videoProcessExchange)
                .with(VIDEO_PROCESS_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding notificationDeadLetterBinding(
            Queue notificationDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(deadLetterExchange)
                .with(NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Queue videoProcessDeadLetterQueue() {
        return QueueBuilder.durable(VIDEO_PROCESS_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding videoProcessDeadLetterBinding(
            Queue videoProcessDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(videoProcessDeadLetterQueue)
                .to(deadLetterExchange)
                .with(VIDEO_PROCESS_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public CustomExchange delayedExchange() {
        return new CustomExchange(
                DELAYED_EXCHANGE,
                "x-delayed-message",
                true,
                false,
                Map.of("x-delayed-type", "direct")
        );
    }

    @Bean
    public Queue reviewTimeoutQueue() {
        return QueueBuilder.durable(REVIEW_TIMEOUT_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding reviewTimeoutBinding(
            Queue reviewTimeoutQueue,
            CustomExchange delayedExchange
    ) {
        return BindingBuilder.bind(reviewTimeoutQueue)
                .to(delayedExchange)
                .with(REVIEW_TIMEOUT_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public Queue reviewTimeoutDeadLetterQueue() {
        return QueueBuilder.durable(REVIEW_TIMEOUT_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding reviewTimeoutDeadLetterBinding(
            Queue reviewTimeoutDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(reviewTimeoutDeadLetterQueue)
                .to(deadLetterExchange)
                .with(REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Queue resourcePurgeQueue() {
        return QueueBuilder.durable(RESOURCE_PURGE_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RESOURCE_PURGE_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding resourcePurgeBinding(
            Queue resourcePurgeQueue,
            CustomExchange delayedExchange
    ) {
        return BindingBuilder.bind(resourcePurgeQueue)
                .to(delayedExchange)
                .with(RESOURCE_PURGE_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public Queue resourcePurgeDeadLetterQueue() {
        return QueueBuilder.durable(RESOURCE_PURGE_DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding resourcePurgeDeadLetterBinding(
            Queue resourcePurgeDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(resourcePurgeDeadLetterQueue)
                .to(deadLetterExchange)
                .with(RESOURCE_PURGE_DEAD_LETTER_ROUTING_KEY);
    }
}
