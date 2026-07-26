package com.example.demo.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoListItemVO;
import org.springframework.stereotype.Service;
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

import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VideoServiceImpl implements VideoService {

    private final VideoMapper videoMapper;

    private final VideoCategoryMapper videoCategoryMapper;
    private final MinioService minioService;
    private final HotRankService hotRankService;
    private final RedisTemplate<String, Object> redisTemplate;

    public VideoServiceImpl(
            VideoMapper videoMapper,
            VideoCategoryMapper videoCategoryMapper,
            MinioService minioService,
            HotRankService hotRankService,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.videoMapper = videoMapper;
        this.videoCategoryMapper = videoCategoryMapper;
        this.minioService = minioService;
        this.hotRankService = hotRankService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            long page,
            long size
    ) {
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);

        IPage<VideoListItemVO> pageData =
                videoMapper.selectPublishedPage(pageRequest, categoryId);

        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
        });

        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        int affectedRows = videoMapper.increaseViewCount(videoId);

        if (affectedRows == 0) {
            throw new BusinessException(404, "视频不存在、未发布或已下架");
        }

        String cacheKey = RedisKeys.videoDetail(videoId);

        Object cachedValue = redisTemplate.opsForValue().get(cacheKey);

        VideoDetailVO videoDetail;

        if (cachedValue instanceof VideoDetailVO cachedDetail) {
            videoDetail = cachedDetail;

            // 当前访问也会增加播放量，因此同步更新缓存中的播放量。
            videoDetail.setViewCount(videoDetail.getViewCount() + 1);

            redisTemplate.opsForValue().set(
                    cacheKey,
                    videoDetail,
                    30,
                    TimeUnit.MINUTES
            );
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
        videoDetail.setCoverUrl(
                minioService.getAccessUrl(videoDetail.getCoverUrl())
        );

        videoDetail.setVideoUrl(
                minioService.getAccessUrl(videoDetail.getVideoUrl())
        );

        hotRankService.addPlayScore(videoId);

        return videoDetail;
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

        if (!request.getCoverObjectName().startsWith("cover/")) {
            throw new BusinessException(400, "封面文件路径不合法");
        }

        if (!request.getVideoObjectName().startsWith("video/")) {
            throw new BusinessException(400, "视频文件路径不合法");
        }

        Video video = new Video();
        video.setAuthorId(currentUser.userId());
        video.setCategoryId(request.getCategoryId());
        video.setTitle(request.getTitle());
        video.setDescription(request.getDescription());
        video.setCoverUrl(request.getCoverObjectName());
        video.setVideoUrl(request.getVideoObjectName());
        video.setDuration(request.getDuration());
        video.setStatus("PENDING");
        video.setViewCount(0L);
        video.setLikeCount(0L);
        video.setFavoriteCount(0L);

        videoMapper.insertCreatorVideo(video);

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

        if (!request.getCoverObjectName().startsWith("cover/")) {
            throw new BusinessException(400, "封面文件路径不合法");
        }

        if (!request.getVideoObjectName().startsWith("video/")) {
            throw new BusinessException(400, "视频文件路径不合法");
        }
    }

    private Video buildUpdatedVideo(
            Long videoId,
            VideoUpdateRequest request
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
        video.setCoverUrl(request.getCoverObjectName());
        video.setVideoUrl(request.getVideoObjectName());
        video.setDuration(request.getDuration());

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

        Video video = buildUpdatedVideo(videoId, request);

        int rows = videoMapper.updateVideoById(video);

        if (rows == 0) {
            throw new BusinessException(400, "视频更新失败");
        }

        deleteVideoDetailCache(videoId);

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

        int rows = videoMapper.deleteVideoById(videoId);

        if (rows == 0) {
            throw new BusinessException(400, "视频删除失败");
        }

        deleteVideoDetailCache(videoId);

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

        Video video = buildUpdatedVideo(videoId, request);

        int rows = videoMapper.updateVideoById(video);

        if (rows == 0) {
            throw new BusinessException(400, "视频更新失败");
        }

        deleteVideoDetailCache(videoId);

    }

    @Override
    @Transactional
    public void deleteAdminVideo(Long videoId) {
        Video existed = videoMapper.selectById(videoId);

        if (existed == null) {
            throw new BusinessException(404, "视频不存在");
        }

        int rows = videoMapper.deleteVideoById(videoId);

        if (rows == 0) {
            throw new BusinessException(400, "视频删除失败");
        }

        deleteVideoDetailCache(videoId);

    }

    private void deleteVideoDetailCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoDetail(videoId));
    }

}
