package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoListItemVO;

public interface VideoListCacheService {

    PageResult<VideoListItemVO> getFirstPage(Long categoryId, long size);

    void putFirstPage(Long categoryId, long size, PageResult<VideoListItemVO> page);

    void invalidateAll();
}
