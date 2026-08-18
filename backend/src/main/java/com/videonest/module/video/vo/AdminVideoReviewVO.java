package com.videonest.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * VO 管理员视频审核视图对象
 * 场景：管理员后台，待审核/已审核视频列表页面
 * 包含：视频基础信息 + 作者信息 + 分区信息 + 审核相关字段
 */
@Data
public class AdminVideoReviewVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private String videoUrl;

    private Integer duration;            // 视频时长

    private String status;

    private LocalDateTime createTime;

    private Long authorId;

    private String authorUsername;

    private String authorNickname;

    private Long categoryId;

    private String categoryName;

    private String rejectReason;

    private LocalDateTime reviewDeadline;

    // 是否已经发送审核超时通知；0未通知，1已通知；用来避免重复给管理员发送超时提醒
    private Integer reviewTimeoutNotified;

}
