package com.videonest.module.notification.service.impl;

import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.service.NotificationMessagePublisherService;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通知MQ消息生产者实现类
 * 负责将通知领域事件序列化，发送至RabbitMQ交换机
 * 绑定事务后置执行，保证数据库事务提交成功后再发消息，避免消息超前发送导致数据不一致
 */
@Service
@Slf4j
public class NotificationMessagePublisherServiceImpl
        implements NotificationMessagePublisherService {

    private final TransactionalOutboxService outboxService;

    public NotificationMessagePublisherServiceImpl(TransactionalOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    /**
     * 发布通知事件到RabbitMQ的核心方法
     * @param domainEvent 上层传递的通知领域事件包装对象
     */
    @Override
    // 事务事件监听注解：只有在**当前数据库事务完全提交成功之后**，才执行发MQ逻辑
    // 现在改为在当前事务内写入 Outbox；真正的 MQ 投递由后台调度器在提交后执行。
    @EventListener
    public void publish(NotificationDomainEvent domainEvent) {
        // 生成全局唯一的消息ID，用于追踪这条MQ消息，配合ConfirmCallback做投递确认
        String messageId = domainEvent.notificationEvent().eventId();
        {
            // 取出领域事件NotificationDomainEvent内部真正的NotificationEvent事件对象进行序列化
            Object message = domainEvent.notificationEvent();

            // 发送消息到指定交换机
            outboxService.append(
                    messageId,
                    "NOTIFICATION",
                    RabbitMqConfig.NOTIFICATION_EXCHANGE,
                    RabbitMqConfig.NOTIFICATION_ROUTING_KEY,
                    message
            );
            log.info(
                    "通知消息已写入 Outbox，eventId={}，type={}，messageId={}",
                    domainEvent.notificationEvent().eventId(),
                    domainEvent.notificationEvent().type(),
                    messageId
            );
        }
    }
}
