package com.example.demo.module.video.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.dto.VideoCreateRequest;
import com.example.demo.module.video.dto.VideoUpdateRequest;
import com.example.demo.module.video.vo.*;

import java.util.List;


public interface VideoService {


    PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            String keyword,
            long page,
            long size
    );

    VideoDetailVO getPublishedVideoDetail(Long videoId);

    VideoViewReportVO recordView(
            Long videoId,
            String viewerKey,
            String ipHash,
            boolean anonymous
    );

    List<VideoListItemVO> listHotVideos(int limit);

    VideoCreateVO createVideo(VideoCreateRequest request);

    PageResult<AdminVideoReviewVO> listPendingReviewVideos(
            long page,
            long size
    );

    void reviewVideo(
            Long videoId,
            String action,
            String rejectReason
    );

    PageResult<CreatorVideoListVO> listCreatorVideos(
            long page,
            long size
    );

    CreatorProfileVO getCreatorProfile();

    PageResult<VideoListItemVO> listMyLikedVideos(long page, long size);

    PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size);

    // 接口内增加
    void updateCreatorVideo(Long videoId, VideoUpdateRequest request);

    void deleteCreatorVideo(Long videoId);

    void updateAdminVideo(Long videoId, VideoUpdateRequest request);

    void deleteAdminVideo(Long videoId);


}
