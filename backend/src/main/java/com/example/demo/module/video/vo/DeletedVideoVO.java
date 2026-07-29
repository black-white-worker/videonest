package com.example.demo.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeletedVideoVO {

    private Long id;
    private String title;
    private Long authorId;
    private String authorNickname;
    private String coverUrl;
    private String status;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    private LocalDateTime purgeAfter;
    private Integer purgeAttempts;
    private String purgeError;
}
