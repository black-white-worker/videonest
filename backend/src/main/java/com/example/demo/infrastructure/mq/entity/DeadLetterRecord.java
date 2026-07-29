package com.example.demo.infrastructure.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dead_letter_record")
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
