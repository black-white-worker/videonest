package com.example.demo.module.notification.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {

    private Long id;
    private Long actorId;
    private String actorNickname;
    private String type;
    private Long videoId;
    private String videoTitle;
    private Long commentId;
    private String content;
    private Integer isRead;
    private LocalDateTime createTime;
}
