package com.example.demo.module.video.service;

import java.util.List;

public interface HotRankService {

    void addPlayScore(Long videoId);

    void addLikeScore(Long videoId);

    void addFavoriteScore(Long videoId);

    void addCommentScore(Long videoId);

    List<Long> getTopVideoIds(int limit);
}