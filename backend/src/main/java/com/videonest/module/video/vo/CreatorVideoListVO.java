package com.videonest.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * VO：创作者视频列表视图对象
 * 注意：这是【作者自己看】的列表，不是对外公开的游客视频列表
 * 会暴露很多内部字段（转码错误、存储对象名、审核截止时间等），普通用户不能看到这些
 */
@Data
public class CreatorVideoListVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private String videoUrl;

    private Integer duration;

    private String status;

    private String rejectReason;

    // FFmpeg转码失败错误信息，记录转码报错详情，给作者排查问题；对外公开接口不能返回此字段
    private String processError;

    private LocalDateTime reviewDeadline;

    // 审核超时是否通知标记：0未通知，1已通知，避免重复推送提醒
    private Integer reviewTimeoutNotified;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private Long viewCount;

    private Long likeCount;

    private Long favoriteCount;

    private Long categoryId;

    private String categoryName;

    private String coverObjectName;

    private String videoObjectName;
}
