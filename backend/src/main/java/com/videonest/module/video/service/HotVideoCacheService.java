package com.videonest.module.video.service;

import com.videonest.module.video.vo.VideoListItemVO;

import java.util.List;

public interface HotVideoCacheService {

    List<VideoListItemVO> getHotVideos(int limit);

    void refreshRankAndCards();

    void invalidateCards();
}
