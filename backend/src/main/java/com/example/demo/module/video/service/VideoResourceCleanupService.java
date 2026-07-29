package com.example.demo.module.video.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.vo.DeletedVideoVO;

public interface VideoResourceCleanupService {

    PageResult<DeletedVideoVO> listDeletedVideos(long page, long size);

    void purgeVideo(Long videoId);

    void recordPurgeFailure(Long videoId, String error);
}
