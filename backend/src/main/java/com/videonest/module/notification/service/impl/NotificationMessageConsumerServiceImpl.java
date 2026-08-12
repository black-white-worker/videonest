package com.videonest.module.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.module.notification.entity.Notification;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.notification.mapper.NotificationMapper;
import com.videonest.module.notification.service.NotificationMessageConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ通知消息消费者具体实现类
 * 监听通知队列，异步解析消息、做幂等校验、最终插入notification通知表
 */
/**
 * consume 方法先调用 readEvent 工具方法将 MQ 传递的 JSON 字符串反序列化为 NotificationEvent 事件对象，
 * 接着过滤用户自身操作给自己触发的无效通知（审核超时、视频驳回系统通知除外），
 * 随后通过 eventId 查询数据库完成前置幂等校验，再将事件对象属性封装为 Notification 数据库实体执行插入操作，
 * 最后通过 try-catch 捕获数据库 eventId 唯一索引冲突抛出的 DuplicateKeyException 异常，仅打印日志而不向外抛出异常，
 * 使消费流程正常结束并触发 MQ 的 ACK 确认，避免重复消息被退回队列造成无限重试的死循环，依靠前置查询校验加数据库唯一索引兜底实现了完整的消息幂等消费
 * **/
@Service
@Slf4j
public class NotificationMessageConsumerServiceImpl
        implements NotificationMessageConsumerService {

    // Jackson序列化工具，用来把MQ的JSON字符串反序列化为NotificationEvent对象
    private final ObjectMapper objectMapper;
    private final NotificationMapper notificationMapper;

    public NotificationMessageConsumerServiceImpl(
            ObjectMapper objectMapper,
            NotificationMapper notificationMapper
    ) {
        this.objectMapper = objectMapper;
        this.notificationMapper = notificationMapper;
    }

    /**
     * MQ消息消费核心方法
     * @param message RabbitMQ投递过来的JSON字符串消息体
     */
    @Override
    @RabbitListener(queues = RabbitMqConfig.NOTIFICATION_QUEUE)
    public void consume(String message) {
        // 把JSON字符串转为事件对象NotificationEvent
        NotificationEvent event = readEvent(message);

        // 操作人ID == 接收人ID，并且不是审核超时、视频驳回这两种特殊系统通知
        if (event.recipientId().equals(event.actorId())
                && !"REVIEW_TIMEOUT".equals(event.type())
                && !"VIDEO_REJECTED".equals(event.type())) {
            log.debug("忽略自己触发给自己的通知，eventId={}", event.eventId());
            return;
        }

        // 幂等性校验：根据eventId查询数据库是否已经存在该通知
        Long count = notificationMapper.selectCount(
                //LambdaQueryWrapper ：MyBatis-Plus Lambda条件构造器，通过实体类方法引用绑定字段
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getEventId, event.eventId())
        );

        if (count > 0) {
            log.info("通知消息已消费，跳过重复投递，eventId={}", event.eventId());
            return;
        }

        // 将MQ事件对象event 转换为数据库Notification实体
        Notification notification = new Notification();
        notification.setEventId(event.eventId());
        notification.setRecipientId(event.recipientId());
        notification.setActorId(event.actorId());
        notification.setType(event.type());
        notification.setVideoId(event.videoId());
        notification.setCommentId(event.commentId());
        notification.setContent(event.content());
        notification.setIsRead(0);

        try {
            notificationMapper.insert(notification);
            log.info(
                    "通知消息消费成功，eventId={}，type={}，recipientId={}",
                    event.eventId(),
                    event.type(),
                    event.recipientId()
            );
        } catch (DuplicateKeyException e) {
            // 只捕获DuplicateKeyException异常
            // 兜底幂等：数据库eventId字段设置了唯一索引，极端并发下重复插入会报唯一键冲突，
            // 和A相同的信息B重新放回原队列头部，因为原来已经有相同的主键 A 被消费者消费就会报错并且无限重试
            // 捕获异常后直接算作消费成功，避免MQ无限重试死循环
            log.info("通知唯一键冲突，按幂等消费成功处理，eventId={}", event.eventId());
        }
    }

    /**
     * 私有工具方法：解析MQ原始JSON消息为NotificationEvent事件对象
     * @param message MQ原始字符串
     * @return 解析后的事件对象
     */
    private NotificationEvent readEvent(String message) {
        try {
            return objectMapper.readValue(message, NotificationEvent.class);
        } catch (JsonProcessingException e) {
            log.error("通知消息格式错误，payload={}", message, e);
            throw new MessageConversionException("通知消息格式错误", e);
        }
    }
}
