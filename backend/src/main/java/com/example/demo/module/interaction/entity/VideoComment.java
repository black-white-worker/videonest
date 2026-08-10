package com.example.demo.module.interaction.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频评论实体类
 * 对应数据库 video_comment 评论表，实现视频一级评论、二级回复评论存储
 */
@Data
@TableName("video_comment")
public class VideoComment {

    private Long id;
    private Long videoId;
    private Long userId;
    private Long parentId;
    private Long rootId;
    private String content;
    private Integer status;
    private LocalDateTime deletedAt;
    private Long cascadeDeletedRootId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
