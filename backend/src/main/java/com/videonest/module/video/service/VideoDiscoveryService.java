package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoViewReportVO;

import java.util.List;

/**
 * 视频发现业务接口
 * 面向用户侧的视频查询、浏览、播放统计、热门视频
 */
public interface VideoDiscoveryService {

    /**
     * 分页查询已发布的视频列表
     * @param categoryId 分类id；null代表不按分类筛选，查询全部分类
     * @param keyword 搜索关键词；null/空字符串代表不做关键词搜索
     * @param page 当前页码，从1开始
     * @param size 每页条数
     */
    PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId, String keyword, long page, long size
    );

    /**
     * 获取单个已发布视频的详情
     * @param videoId 视频主键id
     * @return VideoDetailVO 视频完整详情信息
     */
    VideoDetailVO getPublishedVideoDetail(Long videoId);

    /**
     * 记录视频播放行为，播放上报接口
     * 统计播放量，区分匿名用户、登录用户，使用viewerKey+ipHash去重防刷播放量
     * @param videoId 被播放的视频id
     * @param viewerKey 观看者唯一标识；登录用户为用户id，匿名用户生成前端随机标识
     * @param ipHash 用户ip哈希值
     * @param anonymous true=匿名游客，false=登录用户
     * @return VideoViewReportVO 上报之后返回给前端的播放统计数据（实时播放数等）
     */
    VideoViewReportVO recordView(
            Long videoId, String viewerKey, String ipHash, boolean anonymous
    );

    /**
     * 查询热门视频列表
     * @param limit 返回多少条热门视频
     * @return List<VideoListItemVO> 热门视频简略列表，一般从Redis缓存读取，不走DB实时查询
     */
    List<VideoListItemVO> listHotVideos(int limit);
}
