package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.CreatorProfileVO;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.vo.VideoListItemVO;

public interface CreatorVideoQueryService {

    CreatorProfileVO getCreatorProfile();

    PageResult<VideoListItemVO> listMyLikedVideos(long page, long size);

    PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size);

    PageResult<CreatorVideoListVO> listCreatorVideos(long page, long size);
}
