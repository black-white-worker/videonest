package com.example.demo.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminVideoReviewVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    private String videoUrl;

    private Integer duration;

    private String status;

    private LocalDateTime createTime;

    private Long authorId;

    private String authorUsername;

    private String authorNickname;

    private Long categoryId;

    private String categoryName;
}