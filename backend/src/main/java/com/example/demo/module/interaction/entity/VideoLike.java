package com.example.demo.module.interaction.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoLike {

    private Long id;

    private Long userId;

    private Long videoId;

    private LocalDateTime createdAt;
}