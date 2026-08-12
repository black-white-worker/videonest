package com.videonest.module.interaction.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频收藏实体类
 * 对应数据库视频收藏记录表，记录用户收藏视频的关联关系
 */
@Data
public class VideoFavorite {

    private Long id;

    private Long userId;

    private Long videoId;

    private LocalDateTime createdAt;
}