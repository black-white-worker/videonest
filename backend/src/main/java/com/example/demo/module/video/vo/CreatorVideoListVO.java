package com.example.demo.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

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

    private String processError;

    private LocalDateTime reviewDeadline;

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
