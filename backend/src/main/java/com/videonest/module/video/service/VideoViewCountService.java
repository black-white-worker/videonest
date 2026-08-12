package com.videonest.module.video.service;

public interface VideoViewCountService {

    ViewRecordResult recordView(
            Long videoId,
            long persistedCount,
            String viewerKey,
            String ipHash,
            boolean anonymous
    );

    void flushPendingViews();

    record ViewRecordResult(boolean accepted, long viewCount) {
    }
}
