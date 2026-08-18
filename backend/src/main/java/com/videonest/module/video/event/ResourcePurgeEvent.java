package com.videonest.module.video.event;

/**
 * 资源彻底删除事件，携带需要清理资源的视频ID
 * 消费者接收到 ResourcePurgeEvent，拿着videoId去数据库查询Video记录，
 * 获取 originalVideoUrl、各清晰度视频地址、封面url，调用MinIO接口删除存储文件；
 * 同时更新video表 purgeAttempts、purgeError、purgeAfter字段。
 */
public record ResourcePurgeEvent(Long videoId) {
}
