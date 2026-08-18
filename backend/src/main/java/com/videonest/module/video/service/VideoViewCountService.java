package com.videonest.module.video.service;

/**
 * 视频播放计数服务接口
 * 职责：处理视频播放记录、去重统计、批量刷写待落地的播放计数
 * 解决：重复点击、匿名用户、IP去重、内存计数异步落库的业务场景
 */
public interface VideoViewCountService {

    /**
     * 记录一次视频播放行为
     * @param videoId 视频主键ID
     * @param persistedCount 数据库中已经持久化保存的播放数（DB里真实值）
     * @param viewerKey 观看者唯一标识：登录用户用userId，匿名用户生成唯一字符串
     * @param ipHash 用户IP经过哈希处理后的字符串，用于匿名用户防重复播放统计
     * @param anonymous 是否为匿名访客 true=未登录，false=登录用户
     * @return ViewRecordResult 本次播放是否被接纳 + 当前统计后的播放数量
     */
    ViewRecordResult recordView(
            Long videoId,
            long persistedCount,
            String viewerKey,
            String ipHash,
            boolean anonymous
    );

    /**
     * 把内存中缓存、待落地的播放计数批量刷新写入数据库
     */
    void flushPendingViews();

    /**
     * @param accepted true代表本次播放有效，计数+1；false代表重复观看，不计入播放量
     * @param viewCount 经过统计之后的视频总播放数（内存计算后的数值，不一定同步数据库）
     */
    record ViewRecordResult(boolean accepted, long viewCount) {
    }
}
