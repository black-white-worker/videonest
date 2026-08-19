package com.videonest.module.interaction.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.interaction.vo.VideoCommentVO;

public interface CommentListCacheService {
    PageResult<VideoCommentVO> getFirstPage(Long videoId, long size);
    void putFirstPage(Long videoId, long size, PageResult<VideoCommentVO> page);
    void invalidate(Long videoId);
}
