package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoListItemVO;

public interface VideoListCacheService {

    PageResult<VideoListItemVO> getPage(Long categoryId, long page, long size);

    void putPage(Long categoryId, long page, long size, PageResult<VideoListItemVO> pageResult);

    void invalidateAll();
}
