package com.videonest.module.video.service;

import com.videonest.module.video.event.VideoProcessEvent;

/**
 * 视频处理事件消息发布器接口。
 * 职责：将视频处理事件持久化到 Outbox，不在请求线程中执行转码。
 */
public interface VideoProcessMessagePublisher {

    /**
     * 将视频处理事件写入 Outbox
     * @param event 视频处理事件对象，封装事件业务数据
     */
    void publish(VideoProcessEvent event);
}
