package com.example.demo.module.interaction.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_comment")
public class VideoComment {

    private Long id;
    private Long videoId;
    private Long userId;
    private Long parentId;
    private String content;
    private Integer status;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
