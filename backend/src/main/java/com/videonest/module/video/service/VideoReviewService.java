package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.AdminVideoReviewVO;

public interface VideoReviewService {

    /**
     * 查询待审核的视频列表（分页）
     * @return 分页结果，内部封装AdminVideoReviewVO（后台审核页展示的数据）
     */
    PageResult<AdminVideoReviewVO> listPendingReviewVideos(long page, long size);

    /**
     * 执行视频审核操作：通过 / 驳回
     * @param videoId 要审核的视频id
     * @param action 审核动作：比如 pass 通过，reject 驳回
     * @param rejectReason 驳回原因，如果action是pass通过，该值可以为null/空
     */
    void reviewVideo(Long videoId, String action, String rejectReason);
}
