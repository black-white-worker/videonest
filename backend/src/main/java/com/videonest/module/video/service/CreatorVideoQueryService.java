package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.CreatorProfileVO;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.vo.VideoListItemVO;

/**
 * 创作者端查询业务接口
 * 专门处理【创作者个人中心】相关查询：创作者信息、自己发布的视频、点赞记录、收藏记录
 */
public interface CreatorVideoQueryService {

    /**
     * 获取创作者个人主页资料
     */
    CreatorProfileVO getCreatorProfile();

    /**
     * 分页查询【我点赞过的视频】
     * @param page 当前页码
     * @param size 每页条数
     */
    PageResult<VideoListItemVO> listMyLikedVideos(long page, long size);

    /**
     * 分页查询【我收藏的视频】
     * @param page 当前页码
     * @param size 每页条数
     */
    PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size);

    /**
     * 分页查询【我自己发布的作品列表】
     * @param page 当前页码
     * @param size 每页条数
     */
    PageResult<CreatorVideoListVO> listCreatorVideos(long page, long size);
}
