package com.videonest.module.video.service;

import com.videonest.module.video.event.VideoProcessEvent;

/**
 * 视频处理事件消息发布器接口
 * 职责：对外提供发布视频处理事件的能力，用于事件驱动架构
 */
public interface VideoProcessMessagePublisher {

    /**
     * 发布视频处理事件
     * @param event 视频处理事件对象，封装事件业务数据
     */
    void publish(VideoProcessEvent event);
}
