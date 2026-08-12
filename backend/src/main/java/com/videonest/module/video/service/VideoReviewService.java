package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.AdminVideoReviewVO;

public interface VideoReviewService {

    PageResult<AdminVideoReviewVO> listPendingReviewVideos(long page, long size);

    void reviewVideo(Long videoId, String action, String rejectReason);
}
