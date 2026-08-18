package com.videonest.infrastructure.outbox.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videonest.common.exception.MessagePublishException;
import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import com.videonest.infrastructure.outbox.mapper.OutboxEventMapper;
import com.videonest.infrastructure.outbox.service.TransactionalOutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 事务型Outbox写入实现类
 * 核心职责：接收业务参数、组装OutboxEvent实体、JSON序列化消息体、插入outbox_event数据表
 * 严格依附外部业务事务，实现业务操作与消息落库原子一致性，只做数据库写入，不操作RabbitMQ
 */
@Service
public class TransactionalOutboxServiceImpl implements TransactionalOutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxServiceImpl(
            OutboxEventMapper outboxEventMapper,
            ObjectMapper objectMapper
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 接口实现方法：构建消息实体并入库
     * @param eventId 外部传入唯一事件ID
     * @param eventType 事件业务类型
     * @param exchange MQ交换机
     * @param routingKey MQ路由键
     * @param payload 原始业务消息对象
     */
    @Override
    @Transactional
    public void append(
            String eventId,
            String eventType,
            String exchange,
            String routingKey,
            Object payload
    ) {
        OutboxEvent event = new OutboxEvent();
        // 处理全局唯一eventId：外部没传就自动生成UUID
        event.setEventId(eventId == null ? UUID.randomUUID().toString() : eventId);
        event.setEventType(eventType);
        event.setExchangeName(exchange);
        event.setRoutingKey(routingKey);
        event.setStatus("PENDING");                 // 初始化状态：PENDING 待投递
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());  // 下次重试时间设为当前时间，定时任务立刻可以扫描到
        try {
            // 将业务对象序列化为JSON字符串存入payload字段
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // 序列化失败抛出自定义业务异常，触发事务回滚
            throw new MessagePublishException(eventType, "事务消息序列化失败", e);
        }
        // MyBatis插入一条记录到outbox_event表
        outboxEventMapper.insert(event);
    }
}
