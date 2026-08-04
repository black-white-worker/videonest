package com.example.demo.infrastructure.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信消息记录表
 * 用于持久化RabbitMQ死信队列消息，避免消息丢失，支持失败任务查询、人工重试、故障溯源
 * 消费死信队列消息时，先写入本表，后续运维人员可根据记录处理失败任务
 */
@Data
@TableName("dead_letter_record")//对应数据库表
public class DeadLetterRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String queueName;
    private String messageType;
    private String businessId;
    private String payload;
    private String failureReason;
    private String status;
    private Long operatorId;
    private LocalDateTime handledAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
