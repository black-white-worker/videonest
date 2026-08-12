package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.DeletedVideoVO;

public interface VideoResourceCleanupService {

    PageResult<DeletedVideoVO> listDeletedVideos(long page, long size);

    void purgeVideo(Long videoId);

    void recordPurgeFailure(Long videoId, String error);
}
