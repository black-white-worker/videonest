package com.example.demo.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.StorageOperationException;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.video.config.ResourceCleanupProperties;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoResourceCleanupService;
import com.example.demo.module.video.vo.DeletedVideoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VideoResourceCleanupServiceImpl
        implements VideoResourceCleanupService {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ResourceCleanupProperties properties;

    public VideoResourceCleanupServiceImpl(
            VideoMapper videoMapper,
            MinioService minioService,
            RedisTemplate<String, Object> redisTemplate,
            ResourceCleanupProperties properties
    ) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public PageResult<DeletedVideoVO> listDeletedVideos(long page, long size) {
        IPage<DeletedVideoVO> pageData = videoMapper.selectDeletedVideoPage(
                new Page<>(page, size)
        );
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public void purgeVideo(Long videoId) {
        String lockKey = RedisKeys.resourcePurgeLock(videoId);
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                10,
                TimeUnit.MINUTES
        );
        if (!Boolean.TRUE.equals(locked)) {
            log.info("资源清理任务正在由其他实例执行，videoId={}", videoId);
            return;
        }

        try {
            Video video = videoMapper.selectDeletedVideoById(videoId);
            if (video == null) {
                log.info("待清理视频已不存在，按幂等成功处理，videoId={}", videoId);
                return;
            }

            for (String objectName : resourceObjectNames(video)) {
                minioService.deleteObject(objectName);
            }

            videoMapper.hardDeleteVideoLikes(videoId);
            videoMapper.hardDeleteVideoFavorites(videoId);
            videoMapper.hardDeleteVideoComments(videoId);
            videoMapper.hardDeleteVideoNotifications(videoId);
            int rows = videoMapper.hardDeleteVideo(videoId);
            if (rows == 0) {
                throw new BusinessException(409, "视频不在回收站中，不能永久删除");
            }

            clearVideoCache(videoId);
            log.info("视频及关联资源永久删除成功，videoId={}", videoId);
        } finally {
            unlock(lockKey, lockToken, "视频资源清理", videoId);
        }
    }

    @Override
    public void recordPurgeFailure(Long videoId, String error) {
        videoMapper.incrementPurgeFailure(videoId, truncate(error, 900));
        log.error("视频资源清理失败已记录，videoId={}，error={}", videoId, error);
    }

    @Scheduled(fixedDelayString = "${resource-cleanup.fixed-delay-milliseconds:3600000}")
    public void cleanupExpiredResources() {
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                RedisKeys.RESOURCE_CLEANUP_JOB_LOCK,
                lockToken,
                Math.max(properties.getFixedDelayMilliseconds(), 60_000),
                TimeUnit.MILLISECONDS
        );
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            List<Long> videoIds = videoMapper.selectDuePurgeVideoIds(
                    LocalDateTime.now(),
                    properties.getBatchSize()
            );
            for (Long videoId : videoIds) {
                try {
                    purgeVideo(videoId);
                } catch (StorageOperationException e) {
                    recordPurgeFailure(videoId, e.getMessage());
                } catch (DataAccessException e) {
                    recordPurgeFailure(videoId, "数据库清理失败：" + e.getMessage());
                }
            }
            if (!videoIds.isEmpty()) {
                log.info("定时资源清理批次执行完成，count={}", videoIds.size());
            }
        } finally {
            unlock(
                    RedisKeys.RESOURCE_CLEANUP_JOB_LOCK,
                    lockToken,
                    "定时资源清理",
                    null
            );
        }
    }

    /**
     * 仅释放当前实例持有的锁，避免超时锁被其他实例重新获取后遭到误删。
     */
    private void unlock(
            String lockKey,
            String lockToken,
            String operation,
            Long videoId
    ) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (RuntimeException e) {
            log.warn(
                    "释放{}锁失败，等待锁自动过期，videoId={}",
                    operation,
                    videoId,
                    e
            );
        }
    }

    private Set<String> resourceObjectNames(Video video) {
        Set<String> names = new LinkedHashSet<>();
        names.add(video.getCoverUrl());
        names.add(video.getOriginalCoverUrl());
        names.add(video.getCoverListUrl());
        names.add(video.getCoverDetailUrl());
        names.add(video.getOriginalVideoUrl());
        names.add(video.getVideoUrl());
        names.add(video.getVideo480pUrl());
        names.add(video.getVideo720pUrl());
        names.add(video.getVideo1080pUrl());
        names.remove(null);
        names.remove("");
        return names;
    }

    private void clearVideoCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoDetail(videoId));
        redisTemplate.delete(RedisKeys.videoLikeCount(videoId));
        redisTemplate.delete(RedisKeys.videoFavoriteCount(videoId));
        redisTemplate.opsForZSet().remove(RedisKeys.VIDEO_HOT_RANK_KEY, videoId);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "未知资源清理错误";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(value.length() - maxLength);
    }
}
