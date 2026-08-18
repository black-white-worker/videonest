package com.videonest.module.video.service;

import com.videonest.module.video.vo.VideoListItemVO;

import java.util.List;

/**
 * 热门视频缓存服务接口
 * 职责：专门处理【热门视频榜单】的缓存逻辑，把热点视频数据放入Redis，减少数据库频繁查询
 */
public interface HotVideoCacheService {

    /**
     * 获取缓存中的热门视频列表
     * @param limit 需要返回多少条热门视频
     * @return 热门视频VO集合，直接可以返回前端
     */
    List<VideoListItemVO> getHotVideos(int limit);

    /**
     * 刷新热门排行以及缓存卡片数据
     * 一般触发时机：定时任务、视频点赞/播放量变更后主动调用
     * 1.重新计算热门视频排行
     * 2.把最新排行数据写入Redis缓存，覆盖旧缓存
     */
    void refreshRankAndCards();

    /**
     * 使缓存卡片失效
     * 只做删除/清空缓存操作，不会重新生成新缓存
     * 调用后下次访问getHotVideos会缓存未命中，需要回源数据库
     */
    void invalidateCards();
}
