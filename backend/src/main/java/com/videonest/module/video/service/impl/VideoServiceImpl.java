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
import com.videonest.module.video.service.VideoListCacheService;
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
    private final VideoListCacheService videoListCacheService;
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
            VideoListCacheService videoListCacheService,
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
        this.videoListCacheService = videoListCacheService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.resourceCleanupProperties = resourceCleanupProperties;
        this.uploadSessionService = uploadSessionService;
    }

    /**
     * 查询已发布视频分页列表
     */
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

    /**
     * 获取已发布视频详情
     */
    @Override
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        return videoDiscoveryService.getPublishedVideoDetail(videoId);
    }

    /**
     * 记录视频播放埋点
     */
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

    /**
     * 获取热门视频列表
     */
    @Override
    public List<VideoListItemVO> listHotVideos(int limit) {
        return videoDiscoveryService.listHotVideos(limit);
    }

    /**
     * 创作者投稿创建视频
     * @ Transactional 当前方法全部运行在事务中，抛出异常全部回滚
     */
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

        // 上传会话校验：确认这个文件是当前用户已经完成上传的，防止伪造未上传文件
        uploadSessionService.assertConfirmed(
                request.getVideoObjectName(), currentUser.userId(), "video"
        );
        if (StringUtils.hasText(request.getCoverObjectName())) {
            uploadSessionService.assertConfirmed(
                    request.getCoverObjectName(), currentUser.userId(), "cover"
            );
        }

        // 组装Video数据库实
        Video video = new Video();
        video.setAuthorId(currentUser.userId());
        video.setCategoryId(request.getCategoryId());
        video.setTitle(request.getTitle());
        video.setDescription(request.getDescription());
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

        /*
         * markUploadsConsumedAfterCommit：事务提交之后，标记上传会话已消费
         * 关键点：不能在事务内直接标记，如果事务回滚，上传会话不能被标记消耗
         * 只有数据库插入成功提交，才标记文件被业务使用
         */
        markUploadsConsumedAfterCommit(
                request.getVideoObjectName(), request.getCoverObjectName()
        );

        // 发布事件 VideoProcessEvent，触发异步FFmpeg转码任务，主线程不阻塞
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

    /**
     * 在事务提交成功之后执行上传会话标记已消费
     * 如果当前不在事务中，直接执行；如果在事务，注册回调afterCommit
     * 避免事务回滚时把上传会话标记为已使用，造成文件无法二次投稿
     * @param videoObjectName 视频上传会话key
     * @param coverObjectName 封面上传会话key
     */
    private void markUploadsConsumedAfterCommit(
            String videoObjectName,
            String coverObjectName
    ) {
        // 把要执行的逻辑打包成任务，保证数据库事务执行完才跑run是吗
        Runnable consume = () -> {
            uploadSessionService.markConsumed(videoObjectName);
            uploadSessionService.markConsumed(coverObjectName);
        };
        // 没有事务：直接执行任务
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            consume.run();
            return;
        }
        // 存在事务：注册事务回调，事务提交成功之后才执行任务
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        consume.run();
                    }
                }
        );
    }

    /**
     * 管理员分页查询待审核视频
     */
    @Override
    public PageResult<AdminVideoReviewVO> listPendingReviewVideos(
            long page,
            long size
    ) {
        return videoReviewService.listPendingReviewVideos(page, size);
    }

    /**
     * 执行视频审核动作 pass/reject
     */
    @Override
    public void reviewVideo(
            Long videoId,
            String action,
            String rejectReason
    ) {
        videoReviewService.reviewVideo(videoId, action, rejectReason);
    }

    /**
     * 获取创作者个人主页信息
     */
    @Override
    public CreatorProfileVO getCreatorProfile() {
        return creatorVideoQueryService.getCreatorProfile();
    }

    /**
     * 查询我点赞过的视频
     */
    @Override
    public PageResult<VideoListItemVO> listMyLikedVideos(long page, long size) {
        return creatorVideoQueryService.listMyLikedVideos(page, size);
    }

    /**
     * 查询我收藏的视频
     */
    @Override
    public PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size) {
        return creatorVideoQueryService.listMyFavoritedVideos(page, size);
    }

    /**
     * 分页查询创作者自己所有稿件
     */
    @Override
    public PageResult<CreatorVideoListVO> listCreatorVideos(
            long page,
            long size
    ) {
        return creatorVideoQueryService.listCreatorVideos(page, size);
    }

    /**
     * 更新视频请求参数校验，创作者更新、管理员更新共用这套校验逻辑
     * 1.校验分类有效启用
     * 2.校验封面路径前缀
     * 3.校验视频路径前缀
     */
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

    /**
     * 构建待更新的Video实体，复用给创作者更新、管理员更新
     * @param videoId 待更新视频ID
     * @param request 前端更新请求
     * @param existed 数据库原有视频实体
     * @return 组装好用于update的Video对象
     */
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
        // 如果前端传入封面则使用新封面，否则保留数据库旧值
        video.setCoverUrl(StringUtils.hasText(request.getCoverObjectName())
                ? request.getCoverObjectName()
                : existed.getCoverUrl());
        // 如果前端传入视频文件则使用新，保留旧值
        video.setVideoUrl(StringUtils.hasText(request.getVideoObjectName())
                ? request.getVideoObjectName()
                : existed.getVideoUrl());
        // 时长null则沿用旧时长
        video.setDuration(request.getDuration() == null
                ? existed.getDuration()
                : request.getDuration());

        return video;
    }

    /**
     * 创作者修改自己视频
     */
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

        // 参数校验
        validateVideoUpdateRequest(request);

        Video video = buildUpdatedVideo(videoId, request, existed);

        int rows = videoMapper.updateVideoById(video);

        if (rows == 0) {
            throw new BusinessException(400, "视频更新失败");
        }

        /*
         * 单个视频详情缓存：事务还未提交就先删除，靠查询时重新生成。
         * 热门聚合列表缓存：一定要等事务 commit 完成后才删除，之后再重新生成，
         * 避免删完缓存立刻被并发请求加载到未提交的脏数据。
         */
        // 删除Redis视频详情缓存，让下一次请求重新查库
        deleteVideoDetailCache(videoId);
        // 事务提交之后失效热门视频缓存
        invalidateHotVideoCardsAfterCommit();
        log.info("创作者更新视频成功，videoId={}，userId={}", videoId, currentUser.userId());

    }

    /**
     * 创作者删除自己视频：软删除，不是直接物理删除
     */
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

        // 获取配置，计算真正物理清理资源的时间点
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

        // 清除详情缓存，失效热门缓存
        deleteVideoDetailCache(videoId);
        deleteVideoViewCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        publishResourcePurgeEvent(videoId, purgeAfter);
        log.info(
                "创作者视频已移入回收站，videoId={}，userId={}，purgeAfter={}",
                videoId,
                currentUser.userId(),
                purgeAfter
        );

    }

    /**
     * 管理员更新任意视频，没有作者权限校验
     */
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

        // 参数校验和创作者共用
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

    /**
     * 管理员软删除任意视频
     */
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
        deleteVideoViewCache(videoId);
        invalidateHotVideoCardsAfterCommit();
        publishResourcePurgeEvent(videoId, purgeAfter);
        log.info(
                "管理员视频已移入回收站，videoId={}，adminId={}，purgeAfter={}",
                videoId,
                adminId,
                purgeAfter
        );

    }

    /**
     * 删除视频详情Redis缓存
     */
    private void deleteVideoDetailCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoDetail(videoId));
    }

    private void deleteVideoViewCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoViewTotal(videoId));
    }

    /**
     * 事务提交成功之后，失效热门视频缓存
     * 只有数据库修改成功，才清理缓存；事务回滚则不清理缓存
     */
    private void invalidateHotVideoCardsAfterCommit() {
        // 没有事务，直接执行失效热门缓存
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hotVideoCacheService.invalidateCards();
            videoListCacheService.invalidateAll();
            return;
        }
        // 有事务，只有事务成功commit提交完成，才执行失效缓存
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        hotVideoCacheService.invalidateCards();
                        videoListCacheService.invalidateAll();
                    }
                }
        );
    }

    /**
     * 发布资源延迟清理事件
     * @param videoId 视频id
     * @param purgeAfter 计划清理时间
     */
    private void publishResourcePurgeEvent(
            Long videoId,
            LocalDateTime purgeAfter
    ) {
        long delayMilliseconds = Math.max(
                Duration.between(LocalDateTime.now(), purgeAfter).toMillis(),
                0
        );
        // 发布领域事件，事件监听会做延迟任务调度，到期删除MinIO上视频、封面文件
        applicationEventPublisher.publishEvent(
                new ResourcePurgeDomainEvent(
                        new ResourcePurgeEvent(videoId),
                        delayMilliseconds
                )
        );
    }

}
