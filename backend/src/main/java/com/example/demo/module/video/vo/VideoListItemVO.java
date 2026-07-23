package com.example.demo.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

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