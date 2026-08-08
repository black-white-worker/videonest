package com.example.demo.module.video.service;

import java.util.List;

public interface HotRankService {

    void addPlayScore(Long videoId);

    void addLikeScore(Long videoId);

    void addFavoriteScore(Long videoId);

    void addCommentScore(Long videoId);

    List<Long> getTopVideoIds(int limit);

    /**
     * 合并最近 24 小时分桶并原子更新当前热榜，供后台任务调用。
     */
    List<Long> refreshCurrentRank();
}
