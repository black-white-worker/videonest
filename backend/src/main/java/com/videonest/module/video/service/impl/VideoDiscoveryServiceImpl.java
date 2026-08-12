package com.videonest.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.service.VideoDiscoveryService;
import com.videonest.module.video.service.VideoViewCountService;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoViewReportVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VideoDiscoveryServiceImpl implements VideoDiscoveryService {

    private static final String NULL_VIDEO = "__NULL_VIDEO__";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final HotRankService hotRankService;
    private final HotVideoCacheService hotVideoCacheService;
    private final VideoViewCountService videoViewCountService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public VideoDiscoveryServiceImpl(
            VideoMapper videoMapper,
            MinioService minioService,
            HotRankService hotRankService,
            HotVideoCacheService hotVideoCacheService,
            VideoViewCountService videoViewCountService,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.hotRankService = hotRankService;
        this.hotVideoCacheService = hotVideoCacheService;
        this.videoViewCountService = videoViewCountService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId, String keyword, long page, long size
    ) {
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);
        IPage<VideoListItemVO> pageData = videoMapper.selectPublishedPage(
                pageRequest,
                categoryId,
                StringUtils.hasText(keyword) ? keyword.trim() : null
        );
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        return PageResult.of(pageData);
    }

    @Override
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        String cacheKey = RedisKeys.videoDetail(videoId);
        VideoDetailVO videoDetail = getCachedVideoDetail(videoId, cacheKey);

        // Redis 中缓存的是 MinIO 对象名，不能缓存临时访问 URL。
        // 每次返回前重新生成 URL，避免 URL 到期。
        if (populateVideoObjectSizes(videoDetail)) {
            setCacheSafely(cacheKey, videoDetail, cacheTtlMinutes(), TimeUnit.MINUTES);
        }

        VideoDetailVO response = new VideoDetailVO();
        BeanUtils.copyProperties(videoDetail, response);
        response.setCoverUrl(minioService.getAccessUrl(response.getCoverUrl()));
        response.setVideoUrl(minioService.getAccessUrl(response.getVideoUrl()));
        response.setVideo480pUrl(minioService.getAccessUrl(response.getVideo480pUrl()));
        response.setVideo720pUrl(minioService.getAccessUrl(response.getVideo720pUrl()));
        response.setVideo1080pUrl(minioService.getAccessUrl(response.getVideo1080pUrl()));
        return response;
    }

    @Override
    public VideoViewReportVO recordView(
            Long videoId, String viewerKey, String ipHash, boolean anonymous
    ) {
        Long persistedCount = videoMapper.selectPublishedViewCountById(videoId);
        if (persistedCount == null) {
            throw new BusinessException(404, "视频不存在");
        }
        VideoViewCountService.ViewRecordResult result = videoViewCountService.recordView(
                videoId, persistedCount, viewerKey, ipHash, anonymous
        );
        if (result.accepted()) {
            hotRankService.addPlayScore(videoId);
        }
        return new VideoViewReportVO(result.accepted(), result.viewCount());
    }

    @Override
    public List<VideoListItemVO> listHotVideos(int limit) {
        return hotVideoCacheService.getHotVideos(limit);
    }

    private VideoDetailVO getCachedVideoDetail(Long videoId, String cacheKey) {
        Object cached = getCacheSafely(cacheKey);
        if (cached instanceof VideoDetailVO detail) {
            return detail;
        }
        if (NULL_VIDEO.equals(cached)) {
            throw new BusinessException(404, "视频不存在");
        }

        String lockKey = RedisKeys.videoDetailLock(videoId);
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        Object afterWait = null;
        try {
            locked = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, 5, TimeUnit.SECONDS));
            if (locked) {
                afterWait = getCacheSafely(cacheKey);
            } else {
                // 短暂让持锁实例回填缓存；超时则直接查库降级，避免请求长时间排队。
                Thread.sleep(40);
                afterWait = getCacheSafely(cacheKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("视频详情缓存不可用，降级查询数据库，videoId={}", videoId, e);
        }

        if (afterWait instanceof VideoDetailVO detail) {
            unlockVideoDetail(lockKey, lockToken, videoId, locked);
            return detail;
        }
        if (NULL_VIDEO.equals(afterWait)) {
            unlockVideoDetail(lockKey, lockToken, videoId, locked);
            throw new BusinessException(404, "视频不存在");
        }

        try {
            VideoDetailVO detail = videoMapper.selectPublishedDetailById(videoId);
            if (detail == null) {
                setCacheSafely(cacheKey, NULL_VIDEO, 2, TimeUnit.MINUTES);
                throw new BusinessException(404, "视频不存在");
            }
            // TTL 加随机抖动，避免同一批缓存同时失效形成雪崩。
            setCacheSafely(cacheKey, detail, cacheTtlMinutes(), TimeUnit.MINUTES);
            return detail;
        } finally {
            unlockVideoDetail(lockKey, lockToken, videoId, locked);
        }
    }

    private void unlockVideoDetail(
            String lockKey, String lockToken, Long videoId, boolean locked
    ) {
        if (!locked) {
            return;
        }
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (RuntimeException e) {
            log.warn("释放视频详情缓存锁失败，videoId={}", videoId, e);
        }
    }

    private Object getCacheSafely(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取缓存失败，key={}", key, e);
            return null;
        }
    }

    private void setCacheSafely(String key, Object value, long ttl, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl, unit);
        } catch (RuntimeException e) {
            log.warn("写入缓存失败，key={}", key, e);
        }
    }

    private long cacheTtlMinutes() {
        return ThreadLocalRandom.current().nextLong(25, 36);
    }

    private boolean populateVideoObjectSizes(VideoDetailVO videoDetail) {
        boolean changed = false;
        if (videoDetail.getVideo480pSizeBytes() == null
                && StringUtils.hasText(videoDetail.getVideo480pUrl())) {
            videoDetail.setVideo480pSizeBytes(getObjectSizeSafely(videoDetail.getVideo480pUrl()));
            changed = videoDetail.getVideo480pSizeBytes() != null;
        }
        String video720pObjectName = StringUtils.hasText(videoDetail.getVideo720pUrl())
                ? videoDetail.getVideo720pUrl() : videoDetail.getVideoUrl();
        if (videoDetail.getVideo720pSizeBytes() == null
                && StringUtils.hasText(video720pObjectName)) {
            videoDetail.setVideo720pSizeBytes(getObjectSizeSafely(video720pObjectName));
            changed = changed || videoDetail.getVideo720pSizeBytes() != null;
        }
        if (videoDetail.getVideo1080pSizeBytes() == null
                && StringUtils.hasText(videoDetail.getVideo1080pUrl())) {
            videoDetail.setVideo1080pSizeBytes(getObjectSizeSafely(videoDetail.getVideo1080pUrl()));
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
}
