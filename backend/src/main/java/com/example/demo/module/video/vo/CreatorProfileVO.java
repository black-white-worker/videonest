package com.example.demo.module.video.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreatorProfileVO {

    private Long userId;

    private String username;

    private String nickname;

    private String role;

    private Long totalVideoCount;

    private Long pendingVideoCount;

    private Long publishedVideoCount;

    private Long rejectedVideoCount;
}