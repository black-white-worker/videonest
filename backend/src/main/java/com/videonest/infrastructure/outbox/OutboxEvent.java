package com.videonest.infrastructure.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("outbox_event")
public class OutboxEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String eventType;
    private String exchangeName;
    private String routingKey;
    private String payload;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime sentAt;
}
