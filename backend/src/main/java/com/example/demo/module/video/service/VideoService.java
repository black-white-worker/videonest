package com.example.demo.module.video.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.vo.VideoListItemVO;

public interface VideoService {

    PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            long page,
            long size
    );
}