package com.videonest.module.video.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.CreatorVideoQueryService;
import com.videonest.module.video.vo.CreatorProfileVO;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import org.springframework.stereotype.Service;

@Service
public class CreatorVideoQueryServiceImpl implements CreatorVideoQueryService {

    private final VideoMapper videoMapper;
    private final MinioService minioService;

    public CreatorVideoQueryServiceImpl(VideoMapper videoMapper, MinioService minioService) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
    }

    @Override
    public CreatorProfileVO getCreatorProfile() {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Long userId = currentUser.userId();
        Long totalVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>().eq(Video::getAuthorId, userId)
        );
        Long pendingVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getStatus, "PENDING")
        );
        Long publishedVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getStatus, "PUBLISHED")
        );
        Long rejectedVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getStatus, "REJECTED")
        );
        return new CreatorProfileVO(
                currentUser.userId(),
                currentUser.username(),
                currentUser.username(),
                currentUser.role(),
                totalVideoCount,
                pendingVideoCount,
                publishedVideoCount,
                rejectedVideoCount
        );
    }

    @Override
    public PageResult<VideoListItemVO> listMyLikedVideos(long page, long size) {
        return listMyInteractionVideos(page, size, true);
    }

    @Override
    public PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size) {
        return listMyInteractionVideos(page, size, false);
    }

    private PageResult<VideoListItemVO> listMyInteractionVideos(
            long page, long size, boolean liked
    ) {
        Long userId = SecurityUtils.getCurrentUser().userId();
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);
        IPage<VideoListItemVO> pageData = liked
                ? videoMapper.selectMyLikedVideoPage(pageRequest, userId)
                : videoMapper.selectMyFavoritedVideoPage(pageRequest, userId);
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        return PageResult.of(pageData);
    }

    @Override
    public PageResult<CreatorVideoListVO> listCreatorVideos(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Page<CreatorVideoListVO> pageRequest = new Page<>(page, size);
        IPage<CreatorVideoListVO> pageData = videoMapper.selectCreatorVideoPage(
                pageRequest, currentUser.userId()
        );
        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            if (video.getVideoUrl() != null && !video.getVideoUrl().isBlank()) {
                video.setVideoUrl(minioService.getAccessUrl(video.getVideoUrl()));
            }
        });
        return PageResult.of(pageData);
    }
}
