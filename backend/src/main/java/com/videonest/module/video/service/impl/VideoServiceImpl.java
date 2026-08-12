package com.videonest.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.service.VideoDiscoveryService;
import com.videonest.module.video.service.VideoReviewService;
import com.videonest.module.video.service.CreatorVideoQueryService;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.vo.VideoListItemVO;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.video.vo.VideoDetailVO;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.module.category.entity.VideoCategory;
import com.videonest.module.category.mapper.VideoCategoryMapper;
import com.videonest.module.video.dto.VideoCreateRequest;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.vo.VideoCreateVO;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import com.videonest.module.video.vo.AdminVideoReviewVO;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videonest.module.video.vo.CreatorProfileVO;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.dto.VideoUpdateRequest;
import com.videonest.module.video.service.HotRankService;
import com.videonest.infrastructure.redis.RedisKeys;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.context.ApplicationEventPublisher;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.event.ResourcePurgeDomainEvent;
import com.videonest.module.video.event.ResourcePurgeEvent;
import com.videonest.module.video.config.ResourceCleanupProperties;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.upload.service.UploadSessionService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    private final VideoMapper videoMapper;

    private final VideoCategoryMapper videoCategoryMapper;
    private final MinioService minioService;
    private final VideoDiscoveryService videoDiscoveryService;
    private final VideoReviewService videoReviewService;
    private final CreatorVideoQueryService creatorVideoQueryService;
    private final HotVideoCacheService hotVideoCacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ResourceCleanupProperties resourceCleanupProperties;
    private final UploadSessionService uploadSessionService;

    public VideoServiceImpl(
            VideoMapper videoMapper,
            VideoCategoryMapper videoCategoryMapper,
            MinioService minioService,
            VideoDiscoveryService videoDiscoveryService,
            VideoReviewService videoReviewService,
            CreatorVideoQueryService creatorVideoQueryService,
            HotVideoCacheService hotVideoCacheService,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate,
            ApplicationEventPublisher applicationEventPublisher,
            ResourceCleanupProperties resourceCleanupProperties,
            UploadSessionService uploadSessionService
    ) {
        this.videoMapper = videoMapper;
        this.videoCategoryMapper = videoCategoryMapper;
        this.minioService = minioService;
        this.videoDiscoveryService = videoDiscoveryService;
        this.videoReviewService = videoReviewService;
        this.creatorVideoQueryService = creatorVideoQueryService;
        this.hotVideoCacheService = hotVideoCacheService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.resourceCleanupProperties = resourceCleanupProperties;
        this.uploadSessionService = uploadSessionService;
    }

    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            String keyword,
            long page,
            long size
    ) {
        return videoDiscoveryService.listPublishedVideos(
                categoryId, keyword, page, size
        );
    }

    @Override
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        return videoDiscoveryService.getPublishedVideoDetail(videoId);
    }

    @Override
    public com.videonest.module.video.vo.VideoViewReportVO recordView(
            Long videoId,
            String viewerKey,
            String ipHash,
            boolean anonymous
    ) {
        return videoDiscoveryService.recordView(
                videoId, viewerKey, ipHash, anonymous
        );
    }

    @Override
    public List<VideoListItemVO> listHotVideos(int limit) {
        return videoDiscoveryService.listHotVideos(limit);
    }
    @Override
    @Transactional
    public VideoCreateVO createVideo(VideoCreateRequest request) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        VideoCategory category =
                videoCategoryMapper.selectById(request.getCategoryId());

        if (category == null || category.getStatus() != 1) {
            throw new BusinessException(400, "视频分区不存在或已停用");
        }

        if (!request.getVideoObjectName().startsWith("video/")) {
            throw new BusinessException(400, "视频文件路径不合法");
        }

        if (StringUtils.hasText(request.getCoverObjectName())
                && !request.getCoverObjectName().startsWith("cover/")) {
            throw new BusinessException(400, "封面文件路径不合法");
        }

        uploadSessionService.assertConfirmed(
                request.getVideoObjectName(), currentUser.userId(), "video"
        );
        if (StringUtils.hasText(request.getCoverObjectName())) {
            uploadSessionService.assertConfirmed(
                    request.getCoverObjectName(), currentUser.userId(), "cover"
            );
        }

        Video video = new Video();
        video.setAuthorId(currentUser.userId());
        video.setCategoryId(request.getCategoryId());
        video.setTitle(request.getTitle());
        video.setDescription(request.getDescription());
        // 原始封面只供异步处理使用，页面永远只读取处理后的缩略图字段。
        video.setCoverUrl(null);
        video.setOriginalCoverUrl(request.getCoverObjectName());
        video.setVideoUrl(null);
        video.setOriginalVideoUrl(request.getVideoObjectName());
        video.setDuration(request.getDuration());
        video.setStatus("PROCESSING");
        video.setViewCount(0L);
        video.setLikeCount(0L);
        video.setFavoriteCount(0L);

        videoMapper.insertCreatorVideo(video);

        markUploadsConsumedAfterCommit(
                request.getVideoObjectName(), request.getCoverObjectName()
        );

        applicationEventPublisher.publishEvent(
                new VideoProcessEvent(video.getId(), video.getOriginalVideoUrl())
        );
        log.info(
                "用户投稿创建成功，videoId={}，authorId={}，status={}",
                video.getId(),
                currentUser.userId(),
                video.getStatus()
        );

        return new VideoCreateVO(video.getId(), video.getStatus(), video.getRejectReason());
    }

    private void markUploadsConsumedAfterCommit(
            String videoObjectName,
            String coverObjectName
    ) {
        Runnable consume = () -> {
            uploadSessionService.markConsumed(videoObjectName);
            uploadSessionService.markConsumed(coverObjectName);
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            consume.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        consume.run();
                    }
                }
        );
    }

    @Override
    public PageResult<AdminVideoReviewVO> listPendingReviewVideos(
            long page,
            long size
    ) {
        return videoReviewService.listPendingReviewVideos(page, size);
    }

    @Override
    public void reviewVideo(
            Long videoId,
            String action,
            String rejectReason
    ) {
        videoReviewService.reviewVideo(videoId, action, rejectReason);
    }
    @Override
    public CreatorProfileVO getCreatorProfile() {
        return creatorVideoQueryService.getCreatorProfile();
    }

    @Override
    public PageResult<VideoListItemVO> listMyLikedVideos(long page, long size) {
        return creatorVideoQueryService.listMyLikedVideos(page, size);
    }

    @Override
    public PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size) {
        return creatorVideoQueryService.listMyFavoritedVideos(page, size);
    }

    @Override
    public PageResult<CreatorVideoListVO> listCreatorVideos(
            long page,
            long size
    ) {
        return creatorVideoQueryService.listCreatorVideos(page, size);
    }
    private void validateVideoUpdateRequest(VideoUpdateRequest request) {
        VideoCategory category =
                videoCategoryMapper.selectById(request.getCategoryId());

        if (category == null || category.getStatus() != 1) {
            throw new BusinessException(400, "视频分区不存在或已停用");
        }

        if (StringUtils.hasText(request.getCoverObjectName())
                && !request.getCoverObjectName().startsWith("cover/")) {
            throw new BusinessException(400, "封面文件路径不合法");
        }

        if (StringUtils.hasText(request.getVideoObjectName())
                && !request.getVideoObjectName().startsWith("video/")
                && !request.getVideoObjectName().startsWith("processed/")) {
            throw new BusinessException(400, "视频文件路径不合法");
        }
    }

    private Video buildUpdatedVideo(
            Long videoId,
            VideoUpdateRequest request,
            Video existed
    ) {
        Video video = new Video();

        video.setId(videoId);
        video.setCategoryId(request.getCategoryId());
        video.setTitle(request.getTitle().trim());
        video.setDescription(
                request.getDescription() == null
                        ? null
                        : request.getDescription().trim()
        );
        video.setCoverUrl(StringUtils.hasText(request.getCoverObjectName())
                ? request.getCoverObjectName()
                : existed.getCoverUrl());
        video.setVideoUrl(StringUtils.hasText(request.getVideoObjectName())
                ? request.getVideoObjectName()
                : existed.getVideoUrl());
        video.setDuration(request.getDuration() == null
                ? existed.getDuration()
                : request.getDuration());

        return video;
    }

    @Override
    @Transactional
    public void updateCreatorVideo(
            Long videoId,
            VideoUpdateRequest request
    ) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        Video existed = videoMapper.selectById(videoId);

        if (existed == null) {
            throw new BusinessException(404, "视频不存在");
        }

        if (!existed.getAuthorId().equals(currentUser.userId())) {
            throw new BusinessException(403, "无权编辑其他用户的视频");
        }

        validateVideoUpdateRequest(request);

        Video video = buildUpdatedVideo(videoId, request, existed);

        int rows = videoMapper.updateVideoById(video);

        if (rows == 0) {
            throw new BusinessException(400, "视频更新失败");
        }

        deleteVideoDetailCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        log.info("创作者更新视频成功，videoId={}，userId={}", videoId, currentUser.userId());

    }

    @Override
    @Transactional
    public void deleteCreatorVideo(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        Video existed = videoMapper.selectById(videoId);

        if (existed == null) {
            throw new BusinessException(404, "视频不存在");
        }

        if (!existed.getAuthorId().equals(currentUser.userId())) {
            throw new BusinessException(403, "无权删除其他用户的视频");
        }

        LocalDateTime purgeAfter = LocalDateTime.now().plusDays(
                resourceCleanupProperties.getRetentionDays()
        );
        int rows = videoMapper.softDeleteVideo(
                videoId,
                currentUser.userId(),
                purgeAfter
        );

        if (rows == 0) {
            throw new BusinessException(400, "视频删除失败");
        }

        deleteVideoDetailCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        publishResourcePurgeEvent(videoId, purgeAfter);
        log.info(
                "创作者视频已移入回收站，videoId={}，userId={}，purgeAfter={}",
                videoId,
                currentUser.userId(),
                purgeAfter
        );

    }

    @Override
    @Transactional
    public void updateAdminVideo(
            Long videoId,
            VideoUpdateRequest request
    ) {
        Video existed = videoMapper.selectById(videoId);

        if (existed == null) {
            throw new BusinessException(404, "视频不存在");
        }

        validateVideoUpdateRequest(request);

        Video video = buildUpdatedVideo(videoId, request, existed);

        int rows = videoMapper.updateVideoById(video);

        if (rows == 0) {
            throw new BusinessException(400, "视频更新失败");
        }

        deleteVideoDetailCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        log.info("管理员更新视频成功，videoId={}", videoId);

    }

    @Override
    @Transactional
    public void deleteAdminVideo(Long videoId) {
        Video existed = videoMapper.selectById(videoId);

        if (existed == null) {
            throw new BusinessException(404, "视频不存在");
        }

        Long adminId = SecurityUtils.getCurrentUser().userId();
        LocalDateTime purgeAfter = LocalDateTime.now().plusDays(
                resourceCleanupProperties.getRetentionDays()
        );
        int rows = videoMapper.softDeleteVideo(videoId, adminId, purgeAfter);

        if (rows == 0) {
            throw new BusinessException(400, "视频删除失败");
        }

        deleteVideoDetailCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        publishResourcePurgeEvent(videoId, purgeAfter);
        log.info(
                "管理员视频已移入回收站，videoId={}，adminId={}，purgeAfter={}",
                videoId,
                adminId,
                purgeAfter
        );

    }

    private void deleteVideoDetailCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoDetail(videoId));
    }

    private void invalidateHotVideoCardsAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hotVideoCacheService.invalidateCards();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        hotVideoCacheService.invalidateCards();
                    }
                }
        );
    }

    private void publishResourcePurgeEvent(
            Long videoId,
            LocalDateTime purgeAfter
    ) {
        long delayMilliseconds = Math.max(
                Duration.between(LocalDateTime.now(), purgeAfter).toMillis(),
                0
        );
        applicationEventPublisher.publishEvent(
                new ResourcePurgeDomainEvent(
                        new ResourcePurgeEvent(videoId),
                        delayMilliseconds
                )
        );
    }

}
