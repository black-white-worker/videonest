package com.videonest.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * VO：视频详情视图对象
 * 使用场景：视频播放页，展示视频信息、多清晰度播放地址、统计数据、作者信息、分区信息
 * 数据来源：video主表 + user用户表 + category分区表联查组装
 */
@Data
public class VideoDetailVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private String videoUrl;

    private String video480pUrl;

    private String video720pUrl;

    private String video1080pUrl;

    private Long video480pSizeBytes;

    private Long video720pSizeBytes;

    private Long video1080pSizeBytes;

    private Integer duration;

    private Long viewCount;

    private Long likeCount;

    private Long favoriteCount;

    private LocalDateTime publishTime;

    private Long authorId;

    private String authorUsername;

    private String authorNickname;

    private Long categoryId;

    private String categoryName;
}
