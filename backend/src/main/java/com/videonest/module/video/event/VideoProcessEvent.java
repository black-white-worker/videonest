package com.videonest.module.video.event;

/**
 * 视频上传完成后，触发视频转码处理事件
 * 通知转码消费者：对哪条视频、MinIO里哪个原始源文件做转码处理
 * */
public record VideoProcessEvent(
        Long videoId,
        String sourceObjectName
) {
}