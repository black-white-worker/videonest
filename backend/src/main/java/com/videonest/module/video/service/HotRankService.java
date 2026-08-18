package com.videonest.module.video.service;

import java.util.List;

/**
 * 视频热榜服务接口
 * 负责热榜分数统计、热度加分、计算获取TOP热榜ID列表，供定时任务调用刷新热榜
 */
public interface HotRankService {

    /**
     * 增加播放热度分数
     */
    void addPlayScore(Long videoId);

    /**
     * 增加点赞热度分数
     */
    void addLikeScore(Long videoId);

    /**
     * 增加收藏热度分数
     */
    void addFavoriteScore(Long videoId);

    /**
     * 增加评论热度分数
     */
    void addCommentScore(Long videoId);

    /**
     * 获取热度最高的N个视频ID，对外给热榜查询使用
     * @param limit 返回多少条
     * @return 有序视频ID列表（热度从高到低）
     */
    List<Long> getTopVideoIds(int limit);

    /**
     * 合并最近24小时分桶并原子更新当前热榜，供后台定时任务调用。
     * 核心：把各个时间桶的热度合并计算，生成最新热榜ID集合
     * @return 最新排序完成的热榜视频ID列表
     */
    List<Long> refreshCurrentRank();
}
