package com.example.demo.module.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private Long recipientId;
    private Long actorId;
    private String type;
    private Long videoId;
    private Long commentId;
    private String content;
    private Integer isRead;
    private LocalDateTime createTime;
}