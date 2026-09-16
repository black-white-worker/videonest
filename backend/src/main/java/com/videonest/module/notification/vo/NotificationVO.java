package com.videonest.module.notification.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * NotificationVO 返回数据对象。
 */
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
