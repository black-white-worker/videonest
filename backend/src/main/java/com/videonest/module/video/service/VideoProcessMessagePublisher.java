package com.videonest.module.video.service;

import com.videonest.module.video.event.VideoProcessEvent;

public interface VideoProcessMessagePublisher {

    void publish(VideoProcessEvent event);
}
