package com.videonest.infrastructure.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OutboxEvent 事务发件箱事件实体
 * 对应数据库 outbox_event 表，实现Transactional Outbox事务发件箱模式
 * 核心作用：在本地数据库事务中记录待发送MQ消息，由定时任务异步投递，保证数据库操作与消息发送最终一致性
 */
@Data
@TableName("outbox_event")
public class OutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String eventType;
    private String exchangeName;
    private String routingKey;
    private String payload;         // 消息实际载荷，JSON字符串格式存储业务完整数据，投递时直接序列化发送到MQ
    private String status;
    private Integer retryCount;     // 消息失败重试次数，每次投递失败自动+1，可配置最大重试次数防止死循环
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime sentAt;
}
