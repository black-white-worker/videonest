package com.videonest.module.interaction.service.impl;

import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.interaction.entity.VideoFavorite;
import com.videonest.module.interaction.entity.VideoLike;
import com.videonest.module.interaction.mapper.VideoFavoriteMapper;
import com.videonest.module.interaction.mapper.VideoLikeMapper;
import com.videonest.module.interaction.service.InteractionService;
import com.videonest.module.interaction.vo.InteractionStatusVO;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * 视频互动业务层实现类
 * 负责视频点赞、取消点赞、收藏、取消收藏、查询互动状态、缓存刷新、消息事件发布等核心逻辑
 */
/**
 * 写操作（改数据）：先 DB，后 Redis；读操作（查数据）：先 Redis，后 DB
 * */
/**
 * refreshFavoriteCache 等缓存刷新工具方法，本质是通过 redisTemplate.opsForValue() 操作 Redis String 结构，
 * 调用带超时参数的 set 方法写入 KV 并自动设置键过期时间，实现缓存数据的更新与自动失效。
 * */
/**
 * 对于类似updateDetailLikeCount的视频详情的更新方法，视频详情 VO 无对应数据表无法通过 Mapper 更新，
 * 该方法仅在 Redis 缓存中取出 VO 对象修改收藏数字段后重新写入，只做缓存一致性修复，不执行任何数据库操作。
 * */
@Service
@Slf4j
public class InteractionServiceImpl implements InteractionService {

    private final VideoMapper videoMapper;
    private final VideoLikeMapper videoLikeMapper;
    private final VideoFavoriteMapper videoFavoriteMapper;
    private final HotRankService hotRankService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public InteractionServiceImpl(
            VideoMapper videoMapper,
            VideoLikeMapper videoLikeMapper,
            VideoFavoriteMapper videoFavoriteMapper,
            HotRankService hotRankService,
            RedisTemplate<String, Object> redisTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.videoMapper = videoMapper;
        this.videoLikeMapper = videoLikeMapper;
        this.videoFavoriteMapper = videoFavoriteMapper;
        this.hotRankService = hotRankService;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 查询当前登录用户对指定视频的互动状态
     * 包含：是否点赞、是否收藏、点赞总数、收藏总数
     * @param videoId 视频主键ID
     * @return InteractionStatusVO 封装互动状态返回给前端
     */
    @Override
    public InteractionStatusVO getStatus(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Video video = getPublishedVideo(videoId);

        // 查询当前用户是否点赞该视频（优先走Redis缓存）
        boolean liked = getLikeStatus(videoId, currentUser.userId());
        // 查询当前用户是否收藏该视频（优先走Redis缓存）
        boolean favorited = getFavoriteStatus(videoId, currentUser.userId());

        Long likeCount = getCount(
                RedisKeys.videoLikeCount(videoId),
                video.getLikeCount()
        );

        Long favoriteCount = getCount(
                RedisKeys.videoFavoriteCount(videoId),
                video.getFavoriteCount()
        );

        return new InteractionStatusVO(
                liked,
                favorited,
                likeCount,
                favoriteCount
        );
    }

    /**
     * 视频点赞操作接口
     * 幂等由唯一键 uk_user_video_like 保证：直接插入，冲突即已点过赞，省掉一次“先查再插”。
     * 事务内只做 插入 + 计数自增；Redis 计数、热搜、通知等副作用全部推迟到提交之后，
     * 避免回滚后缓存已经写着“已点赞”、也避免把 Redis 往返时长算进行锁持有时间。
     * @param videoId 被点赞的视频ID
     */
    @Override
    @Transactional
    public void like(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Video video = getPublishedVideo(videoId);

        VideoLike videoLike = new VideoLike();
        videoLike.setUserId(currentUser.userId());
        videoLike.setVideoId(videoId);

        try {
            videoLikeMapper.insert(videoLike);
        } catch (DuplicateKeyException alreadyLiked) {
            // 重复点赞（含并发双写）只是一次空操作，不应打断请求
            return;
        }

        videoMapper.changeLikeCount(videoId, 1);
        Long likeCount = likeCountOf(videoId);

        Long userId = currentUser.userId();
        runAfterCommit(() -> {
            refreshLikeCache(videoId, userId, true, likeCount);
            updateDetailLikeCount(videoId, likeCount);
            hotRankService.addLikeScore(videoId);
            publishNotification(video, userId, "LIKE", "点赞了你的视频");
        });
        log.info("视频点赞成功，videoId={}，userId={}", videoId, userId);
    }

    /**
     * 取消点赞业务方法
     * @param videoId 视频ID
     */
    @Override
    @Transactional
    public void unlike(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        getPublishedVideo(videoId);

        // 根据用户ID+视频ID删除点赞记录，返回受影响行数
        int rows = videoLikeMapper.deleteByUserIdAndVideoId(
                currentUser.userId(),
                videoId
        );

        if (rows == 0) {
            return;
        }

        // video主表点赞数量 -1
        videoMapper.changeLikeCount(videoId, -1);
        Long likeCount = likeCountOf(videoId);

        Long userId = currentUser.userId();
        runAfterCommit(() -> {
            refreshLikeCache(videoId, userId, false, likeCount);
            updateDetailLikeCount(videoId, likeCount);
        });
        log.info("取消视频点赞成功，videoId={}，userId={}", videoId, userId);
    }

    @Override
    @Transactional
    public void favorite(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Video video = getPublishedVideo(videoId);

        VideoFavorite videoFavorite = new VideoFavorite();
        videoFavorite.setUserId(currentUser.userId());
        videoFavorite.setVideoId(videoId);

        try {
            videoFavoriteMapper.insert(videoFavorite);
        } catch (DuplicateKeyException alreadyFavorited) {
            // 唯一键 uk_user_video_favorite 兜底：并发重复收藏只算一次
            return;
        }

        videoMapper.changeFavoriteCount(videoId, 1);
        Long favoriteCount = favoriteCountOf(videoId);

        Long userId = currentUser.userId();
        runAfterCommit(() -> {
            refreshFavoriteCache(videoId, userId, true, favoriteCount);
            updateDetailFavoriteCount(videoId, favoriteCount);
            hotRankService.addFavoriteScore(videoId);
            publishNotification(video, userId, "FAVORITE", "收藏了你的视频");
        });
        log.info("视频收藏成功，videoId={}，userId={}", videoId, userId);
    }

    /**
     * 取消收藏业务方法
     * @param videoId 视频ID
     */
    @Override
    @Transactional
    public void unfavorite(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        getPublishedVideo(videoId);

        int rows = videoFavoriteMapper.deleteByUserIdAndVideoId(
                currentUser.userId(),
                videoId
        );

        if (rows == 0) {
            return;
        }

        videoMapper.changeFavoriteCount(videoId, -1);
        Long favoriteCount = favoriteCountOf(videoId);

        Long userId = currentUser.userId();
        runAfterCommit(() -> {
            refreshFavoriteCache(videoId, userId, false, favoriteCount);
            updateDetailFavoriteCount(videoId, favoriteCount);
        });
        log.info("取消视频收藏成功，videoId={}，userId={}", videoId, userId);
    }

    /** 只读单列计数，取不到时按 0 处理（视频被并发下架等边界）。 */
    private Long likeCountOf(Long videoId) {
        Long count = videoMapper.selectLikeCountById(videoId);
        return count == null ? 0L : count;
    }

    private Long favoriteCountOf(Long videoId) {
        Long count = videoMapper.selectFavoriteCountById(videoId);
        return count == null ? 0L : count;
    }

    /**
     * 把缓存/热度/通知等副作用推迟到事务提交成功之后执行。
     * 没有事务时（例如被非事务调用方直接调用）立即执行，保持原行为。
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            action.run();
                        } catch (RuntimeException e) {
                            // 提交已经成功，副作用失败只能降级：缓存由 TTL 自愈，通知由 Outbox 重试
                            log.warn("点赞/收藏提交后置动作执行失败，将由缓存 TTL 与 Outbox 重试兜底", e);
                        }
                    }
                }
        );
    }

    /**
     * 私有工具方法：校验视频是否存在且状态为已发布
     * @param videoId 视频ID
     * @return 合法的Video实体
     */
    private Video getPublishedVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);

        if (video == null || !"PUBLISHED".equals(video.getStatus())) {
            throw new BusinessException(404, "视频不存在、未发布或已下架");
        }

        return video;
    }

    /**
     * 私有工具：获取单个用户对单个视频的点赞状态（Redis缓存优先）
     * @param videoId 视频ID
     * @param userId 用户ID
     * @return true已点赞 / false未点赞
     */
    private boolean getLikeStatus(Long videoId, Long userId) {
        String key = RedisKeys.videoLikeStatus(videoId, userId);
        // 从Redis读取缓存值
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof Boolean status) {
            return status;
        }

        // 缓存未命中，查询数据库判断点赞记录条数
        boolean status = videoLikeMapper.countByUserIdAndVideoId(
                userId,
                videoId
        ) > 0;

        redisTemplate.opsForValue().set(
                key,
                status,
                12,
                TimeUnit.HOURS
        );

        return status;
    }

    /**
     * 私有工具：获取单个用户对单个视频的收藏状态（Redis缓存优先）
     * @param videoId 视频ID
     * @param userId 用户ID
     * @return true已收藏 / false未收藏
     */
    private boolean getFavoriteStatus(Long videoId, Long userId) {
        String key = RedisKeys.videoFavoriteStatus(videoId, userId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof Boolean status) {
            return status;
        }

        boolean status = videoFavoriteMapper.countByUserIdAndVideoId(
                userId,
                videoId
        ) > 0;

        redisTemplate.opsForValue().set(
                key,
                status,
                12,
                TimeUnit.HOURS
        );

        return status;
    }

    /**
     * 私有通用工具：获取点赞/收藏全局总数，缓存优先
     * @param key Redis缓存key
     * @param databaseCount 数据库查询出来的数值
     * @return 最终统计数量
     */
    private Long getCount(String key, Long databaseCount) {
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof Number count) {
            return count.longValue();
        }

        // 缓存不存在，把数据库值存入Redis。TTL 加 0~300 秒抖动：
        // 热门视频的计数缓存是同一时刻批量回填的，固定 30 分钟会让它们同一秒集体过期，
        // 下一波请求全部回表和回写，形成可预测的尖峰。
        redisTemplate.opsForValue().set(
                key,
                databaseCount,
                30 * 60L + ThreadLocalRandom.current().nextLong(0, 300),
                TimeUnit.SECONDS
        );

        return databaseCount;
    }

    /**
     * 私有工具：点赞操作后统一刷新Redis两块缓存（用户点赞状态 + 全局点赞总数）
     * @param videoId 视频ID
     * @param userId 用户ID
     * @param liked 是否点赞
     * @param likeCount 最新点赞总数
     */
    private void refreshLikeCache(
            Long videoId,
            Long userId,
            boolean liked,
            Long likeCount
    ) {
        // 刷新当前用户该视频点赞状态缓存，12小时过期
        redisTemplate.opsForValue().set(
                RedisKeys.videoLikeStatus(videoId, userId),
                liked,
                12,
                TimeUnit.HOURS
        );

        // 刷新视频全局点赞总数量缓存，30分钟过期
        redisTemplate.opsForValue().set(
                RedisKeys.videoLikeCount(videoId),
                likeCount,
                30,
                TimeUnit.MINUTES
        );
    }

    /**
     * 私有工具：收藏操作后刷新Redis两块缓存（用户收藏状态 + 全局收藏总数）
     * @param videoId 视频ID
     * @param userId 用户ID
     * @param favorited 是否收藏
     * @param favoriteCount 最新收藏总数
     */
    private void refreshFavoriteCache(
            Long videoId,
            Long userId,
            boolean favorited,
            Long favoriteCount
    ) {
        // 更新用户维度收藏状态缓存
        redisTemplate.opsForValue().set(
                RedisKeys.videoFavoriteStatus(videoId, userId),
                favorited,
                12,
                TimeUnit.HOURS
        );

        // 更新视频全局收藏总数缓存
        redisTemplate.opsForValue().set(
                RedisKeys.videoFavoriteCount(videoId),
                favoriteCount,
                30,
                TimeUnit.MINUTES
        );
    }

    /**
     * 私有工具：更新Redis中视频详情缓存VO里的点赞数，保证详情页展示一致。
     * 保留原 key 剩余 TTL：原来每次点赞都把 TTL 重置成 30 分钟，热点视频的详情缓存
     * 会因此永不过期，与“详情缓存 25~36 分钟抖动静默过期”的设计相冲突。
     * @param videoId 视频ID
     * @param likeCount 最新点赞数量
     */
    private void updateDetailLikeCount(Long videoId, Long likeCount) {
        String key = RedisKeys.videoDetail(videoId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof VideoDetailVO videoDetail) {
            videoDetail.setLikeCount(likeCount);
            redisTemplate.opsForValue().set(
                    key,
                    videoDetail,
                    remainingTtlSeconds(key),
                    TimeUnit.SECONDS
            );
        }
    }

    /**
     * 私有工具：更新Redis视频详情缓存VO中的收藏数量（同样保留剩余 TTL）
     * @param videoId 视频ID
     * @param favoriteCount 最新收藏总数
     */
    private void updateDetailFavoriteCount(
            Long videoId,
            Long favoriteCount
    ) {
        String key = RedisKeys.videoDetail(videoId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof VideoDetailVO videoDetail) {
            // 取出缓存对象，改计数后按原剩余 TTL 回写，避免顺手把过期时间重置成 30 分钟
            videoDetail.setFavoriteCount(favoriteCount);
            redisTemplate.opsForValue().set(
                    key,
                    videoDetail,
                    remainingTtlSeconds(key),
                    TimeUnit.SECONDS
            );
        }
    }

    /** 取详情缓存剩余 TTL；key 已无过期时间或查询失败时退回 30 分钟。 */
    private long remainingTtlSeconds(String key) {
        try {
            Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return remaining != null && remaining > 0 ? remaining : 1800L;
        } catch (RuntimeException e) {
            return 1800L;
        }
    }

    /**
     * 私有工具：发布Spring应用事件，触发消息通知（观察者模式解耦通知模块）
     * @param video 被操作的视频
     * @param actorId 操作人ID（点赞/收藏的用户）
     * @param type 操作类型 LIKE/FAVORITE
     * @param content 通知文案
     */
    private void publishNotification(
            Video video,
            Long actorId,
            String type,
            String content
    ) {
        eventPublisher.publishEvent(
                new NotificationDomainEvent(
                        new NotificationEvent(
                                UUID.randomUUID().toString(),
                                video.getAuthorId(),
                                actorId,
                                type,
                                video.getId(),
                                null,
                                content
                        )
                )
        );
    }
}
