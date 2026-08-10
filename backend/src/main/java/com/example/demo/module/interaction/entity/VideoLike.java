package com.example.demo.module.interaction.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频点赞实体类 VideoLike
 * 对应数据库 t_video_like 点赞表
 * 用来记录哪个用户给哪个视频点了赞，做点赞逻辑、去重、统计点赞数量使用
 */
@Data
public class VideoLike {

    private Long id;

    private Long userId;

    private Long videoId;

    private LocalDateTime createdAt;
}