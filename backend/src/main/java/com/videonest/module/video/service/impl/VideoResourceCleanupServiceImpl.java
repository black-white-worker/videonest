package com.videonest.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.common.exception.StorageOperationException;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.config.ResourceCleanupProperties;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.VideoResourceCleanupService;
import com.videonest.module.video.vo.DeletedVideoVO;
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

/**
 * 视频资源清理服务实现类
 * 分页查询回收站视频、手动彻底删除视频资源、定时任务自动过期清理、Redis分布式锁防止多实例并发清理、
 * 删除MinIO存储文件、清理数据库关联数据、清理Redis缓存、记录清理失败错误信息
 */
@Slf4j
@Service
public class VideoResourceCleanupServiceImpl
        implements VideoResourceCleanupService {

    /**
     * Redis Lua解锁脚本，分布式锁解锁
     * KEYS[1]：锁key
     * ARGV[1]：当前线程/实例生成的唯一token
     * 逻辑：只有key存储的值等于当前token，才允许删除锁；防止把别的实例持有的锁误释放
     * 返回1解锁成功；0不做任何操作
     */
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

    /**
     * 分页查询回收站（已软删除）视频列表
     * @param page 当前页码
     * @param size 每页条数
     * @return 分页对象PageResult，返回DeletedVideoVO视图对象
     */
    @Override
    public PageResult<DeletedVideoVO> listDeletedVideos(long page, long size) {
        IPage<DeletedVideoVO> pageData = videoMapper.selectDeletedVideoPage(
                new Page<>(page, size)
        );
        // 把存储中的对象key转换为可访问的http预览url，用于后台页面展示封面
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        return PageResult.of(pageData);
    }

    /**
     * 执行视频彻底物理删除
     * 流程：获取分布式锁 → 查询已软删除视频 → 删除MinIO全部视频封面文件
     * → 硬删除数据库关联表数据（点赞、收藏、评论、通知）→ 硬删除video主表记录
     * → 删除Redis缓存 → finally释放分布式锁
     */
    @Override
    @Transactional
    public void purgeVideo(Long videoId) {
        String lockKey = RedisKeys.resourcePurgeLock(videoId);
        String lockToken = UUID.randomUUID().toString();
        // setIfAbsent：key不存在才设置，过期时间10分钟；防止死锁
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                10,
                TimeUnit.MINUTES
        );
        // 获取锁失败，代表别的服务实例正在处理这条视频，直接返回，避免重复执行
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

            // 获取该视频全部minio文件对象名，循环删除存储上的视频、封面资源
            for (String objectName : resourceObjectNames(video)) {
                minioService.deleteObject(objectName);
            }

            // 硬删除视频关联数据
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

    /**
     * 记录清理失败，更新数据库该视频清理失败字段，记录错误文本
     * @param videoId 视频id
     * @param error 异常错误信息
     */
    @Override
    public void recordPurgeFailure(Long videoId, String error) {
        videoMapper.incrementPurgeFailure(videoId, truncate(error, 900));
        log.error("视频资源清理失败已记录，videoId={}，error={}", videoId, error);
    }

    /**
     * Spring定时任务：自动清理到期回收站视频
     * fixedDelayString：上一次任务执行完成之后间隔指定毫秒再跑下一次；默认3600000ms=1小时
     */
    @Scheduled(fixedDelayString = "${resource-cleanup.fixed-delay-milliseconds:3600000}")
    public void cleanupExpiredResources() {
        String lockToken = UUID.randomUUID().toString();
        // 获取定时任务分布式锁，过期时间取配置的任务间隔与60s的最大值，防止任务未完成锁过期
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
            // 查询满足到期时间条件、待物理删除的视频ID列表，限制batchSize批次大小
            List<Long> videoIds = videoMapper.selectDuePurgeVideoIds(
                    LocalDateTime.now(),
                    properties.getBatchSize()
            );
            for (Long videoId : videoIds) {
                try {
                    purgeVideo(videoId);
                } catch (StorageOperationException e) {
                    // MinIO存储异常，记录清理失败
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

    /**
     * 收集一条视频所有MinIO存储对象路径
     * @param video 视频实体
     * @return 存储对象名称集合
     */
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

    /**
     * 清理视频全部相关Redis缓存
     * @param videoId 视频主键
     */
    private void clearVideoCache(Long videoId) {
        redisTemplate.delete(RedisKeys.videoDetail(videoId));
        redisTemplate.delete(RedisKeys.videoLikeCount(videoId));
        redisTemplate.delete(RedisKeys.videoFavoriteCount(videoId));
        redisTemplate.opsForZSet().remove(RedisKeys.VIDEO_HOT_RANK_KEY, videoId);
    }

    /**
     * 错误信息字符串截断，限制存入数据库最大长度，避免超长文本报错
     * @param value 原始错误字符串
     * @param maxLength 最大允许字符数
     * @return 截断后字符串；null返回默认提示文本
     */
    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "未知资源清理错误";
        }
        return value.length() <= maxLength
                ? value
                : value.substring(value.length() - maxLength);
    }
}
