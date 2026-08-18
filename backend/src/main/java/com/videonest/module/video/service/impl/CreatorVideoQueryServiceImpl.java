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

/**
 * 创作者查询服务实现
 */
@Service
public class CreatorVideoQueryServiceImpl implements CreatorVideoQueryService {

    private final VideoMapper videoMapper;
    private final MinioService minioService;

    public CreatorVideoQueryServiceImpl(VideoMapper videoMapper, MinioService minioService) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
    }

    /**
     * 获取创作者主页统计信息
     * 返回：用户ID、用户名、全部作品数、待审核数、已发布数、驳回数
     */
    @Override
    public CreatorProfileVO getCreatorProfile() {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Long userId = currentUser.userId();

        // 查询该用户全部视频总数量
        Long totalVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>().eq(Video::getAuthorId, userId)
        );
        // 查询状态=PENDING 待审核视频数量
        Long pendingVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getStatus, "PENDING")
        );
        // 查询状态=PUBLISHED 已发布视频数量
        Long publishedVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
                        .eq(Video::getStatus, "PUBLISHED")
        );
        // 查询状态=REJECTED 审核驳回视频数量
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

    /**
     * 分页查询我点赞过的视频
     */
    @Override
    public PageResult<VideoListItemVO> listMyLikedVideos(long page, long size) {
        return listMyInteractionVideos(page, size, true);
    }

    /**
     * 分页查询我收藏的视频
     */
    @Override
    public PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size) {
        return listMyInteractionVideos(page, size, false);
    }

    /**
     * 私有复用方法：处理【点赞 / 收藏】两套分页查询，抽取公共逻辑，避免代码重复
     * @param page 页码
     * @param size 每页大小
     * @param liked true=点赞；false=收藏
     */
    private PageResult<VideoListItemVO> listMyInteractionVideos(
            long page, long size, boolean liked
    ) {
        Long userId = SecurityUtils.getCurrentUser().userId();
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);
        IPage<VideoListItemVO> pageData = liked
                ? videoMapper.selectMyLikedVideoPage(pageRequest, userId)
                : videoMapper.selectMyFavoritedVideoPage(pageRequest, userId);

        // 遍历分页出来的记录：数据库存的是MinIO内部文件路径，需要生成对外可访问的url
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        return PageResult.of(pageData);
    }

    /**
     * 分页查询我自己发布的全部视频（创作者后台看自己作品，包含待审核、驳回、私密）
     */
    @Override
    public PageResult<CreatorVideoListVO> listCreatorVideos(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Page<CreatorVideoListVO> pageRequest = new Page<>(page, size);
        IPage<CreatorVideoListVO> pageData = videoMapper.selectCreatorVideoPage(
                pageRequest, currentUser.userId()
        );
        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            // 判断不为空，才转换视频url，防止null调用方法报错
            if (video.getVideoUrl() != null && !video.getVideoUrl().isBlank()) {
                video.setVideoUrl(minioService.getAccessUrl(video.getVideoUrl()));
            }
        });
        return PageResult.of(pageData);
    }
}
