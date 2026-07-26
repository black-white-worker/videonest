package com.example.demo.module.interaction.service.impl;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.interaction.entity.VideoFavorite;
import com.example.demo.module.interaction.entity.VideoLike;
import com.example.demo.module.interaction.mapper.VideoFavoriteMapper;
import com.example.demo.module.interaction.mapper.VideoLikeMapper;
import com.example.demo.module.interaction.service.InteractionService;
import com.example.demo.module.interaction.vo.InteractionStatusVO;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.HotRankService;
import com.example.demo.module.video.vo.VideoDetailVO;
import com.example.demo.security.LoginUser;
import com.example.demo.security.SecurityUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
public class InteractionServiceImpl implements InteractionService {

    private final VideoMapper videoMapper;
    private final VideoLikeMapper videoLikeMapper;
    private final VideoFavoriteMapper videoFavoriteMapper;
    private final HotRankService hotRankService;
    private final RedisTemplate<String, Object> redisTemplate;

    public InteractionServiceImpl(
            VideoMapper videoMapper,
            VideoLikeMapper videoLikeMapper,
            VideoFavoriteMapper videoFavoriteMapper,
            HotRankService hotRankService,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.videoMapper = videoMapper;
        this.videoLikeMapper = videoLikeMapper;
        this.videoFavoriteMapper = videoFavoriteMapper;
        this.hotRankService = hotRankService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public InteractionStatusVO getStatus(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        Video video = getPublishedVideo(videoId);

        boolean liked = getLikeStatus(videoId, currentUser.userId());
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

    @Override
    @Transactional
    public void like(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        getPublishedVideo(videoId);

        if (videoLikeMapper.countByUserIdAndVideoId(
                currentUser.userId(), videoId
        ) > 0) {
            return;
        }

        VideoLike videoLike = new VideoLike();
        videoLike.setUserId(currentUser.userId());
        videoLike.setVideoId(videoId);

        videoLikeMapper.insert(videoLike);
        videoMapper.changeLikeCount(videoId, 1);

        Video video = videoMapper.selectById(videoId);

        refreshLikeCache(
                videoId,
                currentUser.userId(),
                true,
                video.getLikeCount()
        );

        updateDetailLikeCount(videoId, video.getLikeCount());
        hotRankService.addLikeScore(videoId);
    }

    @Override
    @Transactional
    public void unlike(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        getPublishedVideo(videoId);

        int rows = videoLikeMapper.deleteByUserIdAndVideoId(
                currentUser.userId(),
                videoId
        );

        if (rows == 0) {
            return;
        }

        videoMapper.changeLikeCount(videoId, -1);

        Video video = videoMapper.selectById(videoId);

        refreshLikeCache(
                videoId,
                currentUser.userId(),
                false,
                video.getLikeCount()
        );

        updateDetailLikeCount(videoId, video.getLikeCount());
    }

    @Override
    @Transactional
    public void favorite(Long videoId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        getPublishedVideo(videoId);

        if (videoFavoriteMapper.countByUserIdAndVideoId(
                currentUser.userId(), videoId
        ) > 0) {
            return;
        }

        VideoFavorite videoFavorite = new VideoFavorite();
        videoFavorite.setUserId(currentUser.userId());
        videoFavorite.setVideoId(videoId);

        videoFavoriteMapper.insert(videoFavorite);
        videoMapper.changeFavoriteCount(videoId, 1);

        Video video = videoMapper.selectById(videoId);

        refreshFavoriteCache(
                videoId,
                currentUser.userId(),
                true,
                video.getFavoriteCount()
        );

        updateDetailFavoriteCount(videoId, video.getFavoriteCount());
        hotRankService.addFavoriteScore(videoId);
    }

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

        Video video = videoMapper.selectById(videoId);

        refreshFavoriteCache(
                videoId,
                currentUser.userId(),
                false,
                video.getFavoriteCount()
        );

        updateDetailFavoriteCount(videoId, video.getFavoriteCount());
    }

    private Video getPublishedVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);

        if (video == null || !"PUBLISHED".equals(video.getStatus())) {
            throw new BusinessException(404, "视频不存在、未发布或已下架");
        }

        return video;
    }

    private boolean getLikeStatus(Long videoId, Long userId) {
        String key = RedisKeys.videoLikeStatus(videoId, userId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof Boolean status) {
            return status;
        }

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

    private Long getCount(String key, Long databaseCount) {
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof Number count) {
            return count.longValue();
        }

        redisTemplate.opsForValue().set(
                key,
                databaseCount,
                30,
                TimeUnit.MINUTES
        );

        return databaseCount;
    }

    private void refreshLikeCache(
            Long videoId,
            Long userId,
            boolean liked,
            Long likeCount
    ) {
        redisTemplate.opsForValue().set(
                RedisKeys.videoLikeStatus(videoId, userId),
                liked,
                12,
                TimeUnit.HOURS
        );

        redisTemplate.opsForValue().set(
                RedisKeys.videoLikeCount(videoId),
                likeCount,
                30,
                TimeUnit.MINUTES
        );
    }

    private void refreshFavoriteCache(
            Long videoId,
            Long userId,
            boolean favorited,
            Long favoriteCount
    ) {
        redisTemplate.opsForValue().set(
                RedisKeys.videoFavoriteStatus(videoId, userId),
                favorited,
                12,
                TimeUnit.HOURS
        );

        redisTemplate.opsForValue().set(
                RedisKeys.videoFavoriteCount(videoId),
                favoriteCount,
                30,
                TimeUnit.MINUTES
        );
    }

    private void updateDetailLikeCount(Long videoId, Long likeCount) {
        String key = RedisKeys.videoDetail(videoId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof VideoDetailVO videoDetail) {
            videoDetail.setLikeCount(likeCount);

            redisTemplate.opsForValue().set(
                    key,
                    videoDetail,
                    30,
                    TimeUnit.MINUTES
            );
        }
    }

    private void updateDetailFavoriteCount(
            Long videoId,
            Long favoriteCount
    ) {
        String key = RedisKeys.videoDetail(videoId);
        Object cachedValue = redisTemplate.opsForValue().get(key);

        if (cachedValue instanceof VideoDetailVO videoDetail) {
            videoDetail.setFavoriteCount(favoriteCount);

            redisTemplate.opsForValue().set(
                    key,
                    videoDetail,
                    30,
                    TimeUnit.MINUTES
            );
        }
    }
}