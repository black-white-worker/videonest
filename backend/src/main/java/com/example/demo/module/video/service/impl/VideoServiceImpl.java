package com.example.demo.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoListItemVO;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.module.video.vo.VideoDetailVO;
import org.springframework.transaction.annotation.Transactional;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.module.category.entity.VideoCategory;
import com.example.demo.module.category.mapper.VideoCategoryMapper;
import com.example.demo.module.video.dto.VideoCreateRequest;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.vo.VideoCreateVO;
import com.example.demo.security.LoginUser;
import com.example.demo.security.SecurityUtils;
import com.example.demo.module.video.vo.AdminVideoReviewVO;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.module.video.vo.CreatorProfileVO;
import com.example.demo.module.video.vo.CreatorVideoListVO;
import com.example.demo.module.video.dto.VideoUpdateRequest;
import com.example.demo.module.video.service.HotRankService;
import com.example.demo.infrastructure.redis.RedisKeys;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.context.ApplicationEventPublisher;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.event.ResourcePurgeDomainEvent;
import com.example.demo.module.video.event.ResourcePurgeEvent;
import com.example.demo.module.video.config.ResourceCleanupProperties;
import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.event.NotificationEvent;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class VideoServiceImpl implements VideoService {

    private final VideoMapper videoMapper;

    private final VideoCategoryMapper videoCategoryMapper;
    private final MinioService minioService;
    private final HotRankService hotRankService;
    private final VideoViewCountService videoViewCountService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ResourceCleanupProperties resourceCleanupProperties;

    public VideoServiceImpl(
            VideoMapper videoMapper,
            VideoCategoryMapper videoCategoryMapper,
            MinioService minioService,
            HotRankService hotRankService,
            VideoViewCountService videoViewCountService,
            RedisTemplate<String, Object> redisTemplate,
            ApplicationEventPublisher applicationEventPublisher,
            ResourceCleanupProperties resourceCleanupProperties
    ) {
        this.videoMapper = videoMapper;
        this.videoCategoryMapper = videoCategoryMapper;
        this.minioService = minioService;
        this.hotRankService = hotRankService;
        this.videoViewCountService = videoViewCountService;
        this.redisTemplate = redisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.resourceCleanupProperties = resourceCleanupProperties;
    }

    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            String keyword,
            long page,
            long size
    ) {
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);

        IPage<VideoListItemVO> pageData =
                videoMapper.selectPublishedPage(
                        pageRequest,
                        categoryId,
                        StringUtils.hasText(keyword) ? keyword.trim() : null
                );

        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
        });

        return PageResult.of(pageData);
    }

    @Override
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        String cacheKey = RedisKeys.videoDetail(videoId);

        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);

        VideoDetailVO videoDetail;

        if (cachedValue instanceof VideoDetailVO cachedDetail) {
            videoDetail = cachedDetail;
        } else {
            // 缓存未命中：查询 MySQL，并写入 Redis。
            videoDetail = videoMapper.selectPublishedDetailById(videoId);

            if (videoDetail == null) {
                throw new BusinessException(404, "视频不存在");
            }

            redisTemplate.opsForValue().set(
                    cacheKey,
                    videoDetail,
                    30,
                    TimeUnit.MINUTES
            );
        }

        // Redis 中缓存的是 MinIO 对象名，不能缓存临时访问 URL。
        // 每次返回前重新生成 URL，避免 URL 到期。
        if (populateVideoObjectSizes(videoDetail)) {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    videoDetail,
                    30,
                    TimeUnit.MINUTES
            );
        }

        VideoDetailVO response = new VideoDetailVO();
        BeanUtils.copyProperties(videoDetail, response);
        long persistedViewCount = videoDetail.getViewCount() == null
                ? 0L
                : videoDetail.getViewCount();
        response.setViewCount(
                videoViewCountService.recordView(videoId, persistedViewCount)
        );
        response.setCoverUrl(
                minioService.getAccessUrl(response.getCoverUrl())
        );

        response.setVideoUrl(
                minioService.getAccessUrl(response.getVideoUrl())
        );
        response.setVideo480pUrl(minioService.getAccessUrl(response.getVideo480pUrl()));
        response.setVideo720pUrl(minioService.getAccessUrl(response.getVideo720pUrl()));
        response.setVideo1080pUrl(minioService.getAccessUrl(response.getVideo1080pUrl()));

        hotRankService.addPlayScore(videoId);

        return response;
    }

    private boolean populateVideoObjectSizes(VideoDetailVO videoDetail) {
        boolean changed = false;

        if (videoDetail.getVideo480pSizeBytes() == null
                && StringUtils.hasText(videoDetail.getVideo480pUrl())) {
            videoDetail.setVideo480pSizeBytes(
                    getObjectSizeSafely(videoDetail.getVideo480pUrl())
            );
            changed = videoDetail.getVideo480pSizeBytes() != null;
        }

        String video720pObjectName = StringUtils.hasText(videoDetail.getVideo720pUrl())
                ? videoDetail.getVideo720pUrl()
                : videoDetail.getVideoUrl();
        if (videoDetail.getVideo720pSizeBytes() == null
                && StringUtils.hasText(video720pObjectName)) {
            videoDetail.setVideo720pSizeBytes(
                    getObjectSizeSafely(video720pObjectName)
            );
            changed = changed || videoDetail.getVideo720pSizeBytes() != null;
        }

        if (videoDetail.getVideo1080pSizeBytes() == null
                && StringUtils.hasText(videoDetail.getVideo1080pUrl())) {
            videoDetail.setVideo1080pSizeBytes(
                    getObjectSizeSafely(videoDetail.getVideo1080pUrl())
            );
            changed = changed || videoDetail.getVideo1080pSizeBytes() != null;
        }

        return changed;
    }

    private Long getObjectSizeSafely(String objectName) {
        try {
            return minioService.getObjectSize(objectName);
        } catch (RuntimeException e) {
            log.warn("获取视频对象大小失败，objectName={}", objectName, e);
            return null;
        }
    }

    @Override
    public List<VideoListItemVO> listHotVideos(int limit) {
        List<Long> videoIds = hotRankService.getTopVideoIds(limit);

        if (videoIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, VideoListItemVO> videosById = new HashMap<>();
        for (VideoListItemVO video : videoMapper.selectPublishedListByIds(videoIds)) {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            videosById.put(video.getId(), video);
        }

        return videoIds.stream()
                .map(videosById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
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

        Video video = new Video();
        video.setAuthorId(currentUser.userId());
        video.setCategoryId(request.getCategoryId());
        video.setTitle(request.getTitle());
        video.setDescription(request.getDescription());
        video.setCoverUrl(request.getCoverObjectName());
        video.setVideoUrl(null);
        video.setOriginalVideoUrl(request.getVideoObjectName());
        video.setDuration(request.getDuration());
        video.setStatus("PROCESSING");
        video.setViewCount(0L);
        video.setLikeCount(0L);
        video.setFavoriteCount(0L);

        videoMapper.insertCreatorVideo(video);

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

    @Override
    public PageResult<AdminVideoReviewVO> listPendingReviewVideos(
            long page,
            long size
    ) {
        Page<AdminVideoReviewVO> pageRequest = new Page<>(page, size);

        IPage<AdminVideoReviewVO> pageData =
                videoMapper.selectPendingReviewPage(pageRequest);

        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(
                    minioService.getAccessUrl(video.getCoverUrl())
            );

            video.setVideoUrl(
                    minioService.getAccessUrl(video.getVideoUrl())
            );
        });

        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public void reviewVideo(
            Long videoId,
            String action,
            String rejectReason
    ) {
        Video reviewedVideo = videoMapper.selectById(videoId);
        String status;
        String finalRejectReason = null;

        if ("APPROVE".equalsIgnoreCase(action)) {
            status = "PUBLISHED";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            status = "REJECTED";

            if (!StringUtils.hasText(rejectReason)) {
                throw new BusinessException(400, "驳回投稿时必须填写驳回原因");
            }

            finalRejectReason = rejectReason.trim();
        } else {
            throw new BusinessException(
                    400,
                    "审核操作只能是 APPROVE 或 REJECT"
            );
        }

        int affectedRows = videoMapper.reviewVideo(
                videoId,
                status,
                finalRejectReason
        );

        if (affectedRows == 0) {
            throw new BusinessException(
                    400,
                    "视频不存在，或该投稿已经完成审核"
            );

        }
        if ("REJECTED".equals(status)) {
            LoginUser admin = SecurityUtils.getCurrentUser();
            applicationEventPublisher.publishEvent(
                    new NotificationDomainEvent(
                            new NotificationEvent(
                                    UUID.randomUUID().toString(),
                                    reviewedVideo.getAuthorId(),
                                    admin.userId(),
                                    "VIDEO_REJECTED",
                                    videoId,
                                    null,
                                    finalRejectReason
                            )
                    )
            );
            log.info(
                    "视频驳回通知事件发布成功，videoId={}，authorId={}，adminId={}",
                    videoId,
                    reviewedVideo.getAuthorId(),
                    admin.userId()
            );
        }
        log.info(
                "管理员审核视频成功，videoId={}，action={}，status={}",
                videoId,
                action,
                status
        );
    }

    @Override
    public CreatorProfileVO getCreatorProfile() {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        Long userId = currentUser.userId();

        Long totalVideoCount = videoMapper.selectCount(
                new LambdaQueryWrapper<Video>()
                        .eq(Video::getAuthorId, userId)
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

    private PageResult<VideoListItemVO> listMyInteractionVideos(long page, long size, boolean liked) {
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
    public PageResult<CreatorVideoListVO> listCreatorVideos(
            long page,
            long size
    ) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        Page<CreatorVideoListVO> pageRequest = new Page<>(page, size);

        IPage<CreatorVideoListVO> pageData =
                videoMapper.selectCreatorVideoPage(
                        pageRequest,
                        currentUser.userId()
                );

        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(
                    minioService.getAccessUrl(video.getCoverUrl())
            );

            if (video.getVideoUrl() != null
                    && !video.getVideoUrl().isBlank()) {
                video.setVideoUrl(
                        minioService.getAccessUrl(video.getVideoUrl())
                );
            }
        });

        return PageResult.of(pageData);
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
