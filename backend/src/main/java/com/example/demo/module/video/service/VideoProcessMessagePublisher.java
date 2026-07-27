package com.example.demo.module.video.service;

import com.example.demo.module.video.event.VideoProcessEvent;

public interface VideoProcessMessagePublisher {

    void publish(VideoProcessEvent event);
}
