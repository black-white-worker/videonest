package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoViewReportVO;

import java.util.List;

public interface VideoDiscoveryService {

    PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId, String keyword, long page, long size
    );

    VideoDetailVO getPublishedVideoDetail(Long videoId);

    VideoViewReportVO recordView(
            Long videoId, String viewerKey, String ipHash, boolean anonymous
    );

    List<VideoListItemVO> listHotVideos(int limit);
}
