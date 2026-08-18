package com.videonest.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * VO：视频列表卡片视图对象
 * 使用场景：首页热门、分类列表、推荐列表。只放卡片展示的少量字段，字段少，适合缓存。
 * 只返回公开数据，不包含播放地址、转码地址、审核信息、MinIO内部对象名。
 * 就是前面 HotVideoCardsCache 里面装的元素。
 */
@Data
public class VideoListItemVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private Integer duration;

    private Long viewCount;

    private Long likeCount;

    private Long favoriteCount;

    private LocalDateTime publishTime;

    private Long authorId;

    private String authorNickname;

    private Long categoryId;

    private String categoryName;
}