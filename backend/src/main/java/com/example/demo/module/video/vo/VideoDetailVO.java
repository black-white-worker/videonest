package com.example.demo.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoDetailVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private String videoUrl;

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