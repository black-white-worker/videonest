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
/*
 * 整套 RabbitMQ 架构包含三台业务交换机：通知直连交换机、视频处理直连交换机、
 * 基于插件实现的延迟交换机，分别对接通知、视频处理、视频审核超时、资源清理四路业务主队列；
 * 所有主队列配置死信转发规则，消息触发死信后统一推送至全局公共死信交换机，
 * 再依靠专属死信路由键分流至四个相互独立的业务死信队列，方便单独监听处理各类失败任务
 * */

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

    // 公共死信交换机名称，所有业务队列的死信统一发送到此交换机
    public static final String DEAD_LETTER_EXCHANGE =
            "videonest.dead.letter.exchange";

    // 通知消息对应的死信队列名称
    public static final String NOTIFICATION_DEAD_LETTER_QUEUE =
            "videonest.notification.dlq";

    // 通知消息死信路由key，死信交换机依靠这个key路由消息到通知死信队列
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

    // 视频处理任务死信队列名称
    public static final String VIDEO_PROCESS_DEAD_LETTER_QUEUE =
            "videonest.video.process.dlq";

    // 视频任务死信路由key
    public static final String VIDEO_PROCESS_DEAD_LETTER_ROUTING_KEY =
            "videonest.video.process.dead";

    // 延迟交换机名称（基于rabbitmq_delayed_message_exchange插件）
    public static final String DELAYED_EXCHANGE =
            "videonest.delayed.exchange";

    // 视频审核超时队列（存放延迟消息）
    public static final String REVIEW_TIMEOUT_QUEUE =
            "videonest.video.review.timeout.queue";

    // 审核超时延迟消息路由key
    public static final String REVIEW_TIMEOUT_ROUTING_KEY =
            "videonest.video.review.timeout";

    // 审核超时队列对应的死信队列
    public static final String REVIEW_TIMEOUT_DEAD_LETTER_QUEUE =
            "videonest.video.review.timeout.dlq";

    // 审核超时死信路由key
    public static final String REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY =
            "videonest.video.review.timeout.dead";

    // 资源清理队列（延迟执行过期资源删除）
    public static final String RESOURCE_PURGE_QUEUE =
            "videonest.resource.purge.queue";

    // 资源清理延迟消息路由key
    public static final String RESOURCE_PURGE_ROUTING_KEY =
            "videonest.resource.purge";

    // 资源清理死信队列
    public static final String RESOURCE_PURGE_DEAD_LETTER_QUEUE =
            "videonest.resource.purge.dlq";

    // 资源清理死信路由key
    public static final String RESOURCE_PURGE_DEAD_LETTER_ROUTING_KEY =
            "videonest.resource.purge.dead";

    /**
     * 创建通知直连交换机实例
     * DirectExchange构造参数说明：
     * 参数1：交换机名称
     * 参数2：durable = true → 持久化，RabbitMQ重启交换机不删除
     * 参数3：autoDelete = false → 没有消费者连接时，交换机不会自动删除
     */
    /***
     * 消息到达【主队列 notificationQueue】正常消费；
     * 消息触发死信条件（消费者 nack 拒绝、消息超时、队列满）；
     * RabbitMQ 读取主队列上配置的两个参数
     * .deadLetterExchange(DEAD_LETTER_EXCHANGE)
     * .deadLetterRoutingKey(NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
     * MQ 内部自动把这条死信消息 发送到公共死信交换机，并且携带路由 key：NOTIFICATION_DEAD_LETTER_ROUTING_KEY。
     * deadLetterExchange 是 Direct 直连交换机，开始匹配：
     * 寻找绑定到自身、并且绑定 key 完全一致的队列；
     * 找到 notificationDeadLetterQueue（通知死信队列），消息投递进去。
     * */
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
    /*
    * 配置描述对象（元数据），告诉 SpringAMQP：
    * “启动后去 RabbitMQ 服务端，声明一个队列，属性如下：持久化、死信交换机、死信路由 key
    * 真正存放消息的队列在 RabbitMQ 服务器上，不在 Java 内存
    * */
    @Bean
    public Queue notificationQueue() {
        /*
        * 先实例化 Queue 对象，仅存在 JVM 内存
        * Spring AMQP 建立 TCP 连接连上 RabbitMQ，
        * */
        //durable(true) = 队列持久化
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
     * 参数：1、交换机名字 2、持久化 3、自动删除
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


    /*
     * 死信队列组成
     * 业务主队列（正常消费消息的队列）
     * 死信交换机 DLX（接收变成死信的消息）
     * 死信队列 DLQ（存放死信）
     * 两条绑定关系
     * 主队列绑定业务交换机
     * 死信队列绑定死信交换机
     * */
    /**
     * 公共死信交换机Bean，所有业务队列死信统一投递到此交换机
     * */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    /**
     * 通知消息死信队列Bean，存放通知队列失败/过期/拒绝的消息
     * */
    @Bean
    public Queue notificationDeadLetterQueue() {
        return QueueBuilder.durable(NOTIFICATION_DEAD_LETTER_QUEUE).build();
    }

    /**
     * 将通知死信队列绑定公共死信交换机
     * */
    @Bean
    public Binding notificationDeadLetterBinding(
            Queue notificationDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(deadLetterExchange)
                .with(NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 视频处理任务死信队列Bean
     * */
    @Bean
    public Queue videoProcessDeadLetterQueue() {
        return QueueBuilder.durable(VIDEO_PROCESS_DEAD_LETTER_QUEUE).build();
    }

    /**
     * 视频死信队列绑定公共死信交换机
     * */
    @Bean
    public Binding videoProcessDeadLetterBinding(
            Queue videoProcessDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(videoProcessDeadLetterQueue)
                .to(deadLetterExchange)
                .with(VIDEO_PROCESS_DEAD_LETTER_ROUTING_KEY);
    }


    /**
     * 自定义延迟交换机（需要提前安装 rabbitmq_delayed_message_exchange 插件）
     * 参数说明：
     * 1.交换机名称
     * 2.交换机类型 x-delayed-message（延迟消息类型）
     * 3.durable持久化
     * 4.autoDelete false不自动删除
     * 5.参数map：指定内部路由模式为direct直连模式
     */
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

    /**
     * 视频审核超时延迟队列Bean
     * */
    @Bean
    public Queue reviewTimeoutQueue() {
        return QueueBuilder.durable(REVIEW_TIMEOUT_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    /**
     * 审核超时队列绑定延迟交换机
     * */
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

    /**
     * 审核超时消息死信队列
     * */
    @Bean
    public Queue reviewTimeoutDeadLetterQueue() {
        return QueueBuilder.durable(REVIEW_TIMEOUT_DEAD_LETTER_QUEUE).build();
    }

    /**
     * 审核超时死信队列绑定公共死信交换机
     * */
    @Bean
    public Binding reviewTimeoutDeadLetterBinding(
            Queue reviewTimeoutDeadLetterQueue,
            DirectExchange deadLetterExchange
    ) {
        return BindingBuilder.bind(reviewTimeoutDeadLetterQueue)
                .to(deadLetterExchange)
                .with(REVIEW_TIMEOUT_DEAD_LETTER_ROUTING_KEY);
    }

    /**
     * 资源清理延迟队列Bean，用于延时删除过期文件/资源
     * */
    @Bean
    public Queue resourcePurgeQueue() {
        return QueueBuilder.durable(RESOURCE_PURGE_QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(RESOURCE_PURGE_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    /**
     * 资源清理队列绑定延迟交换机
     * */
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

    /**
     * 资源清理任务死信队列
     * */
    @Bean
    public Queue resourcePurgeDeadLetterQueue() {
        return QueueBuilder.durable(RESOURCE_PURGE_DEAD_LETTER_QUEUE).build();
    }

    /**
     * 资源清理死信队列绑定公共死信交换机
     * */
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


