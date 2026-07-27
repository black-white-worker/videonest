package com.example.demo.module.video.event;

public record VideoProcessEvent(
        Long videoId,
        String sourceObjectName
) {
}