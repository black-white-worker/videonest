package com.videonest.module.video.event;

public record VideoProcessEvent(
        Long videoId,
        String sourceObjectName
) {
}