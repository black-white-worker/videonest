package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.config.HotRankProperties;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.vo.HotVideoCardsCache;
import com.videonest.module.video.vo.VideoListItemVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotVideoCacheServiceImplTest {

    @Mock
    private HotRankService hotRankService;

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void cacheHitDoesNotQueryRankOrDatabase() {
        VideoListItemVO first = new VideoListItemVO();
        first.setId(9L);
        VideoListItemVO second = new VideoListItemVO();
        second.setId(3L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.VIDEO_HOT_CARDS_KEY))
                .thenReturn(new HotVideoCardsCache(List.of(first, second)));

        HotVideoCacheService service = new HotVideoCacheServiceImpl(
                hotRankService,
                videoMapper,
                minioService,
                redisTemplate,
                stringRedisTemplate,
                new HotRankProperties()
        );

        List<VideoListItemVO> result = service.getHotVideos(1);

        assertEquals(List.of(9L), result.stream()
                .map(VideoListItemVO::getId)
                .toList());
        verifyNoInteractions(hotRankService, videoMapper, minioService);
    }
}
