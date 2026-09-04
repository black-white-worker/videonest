package com.videonest.module.video.service.impl;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.service.VideoListCacheService;
import com.videonest.module.video.vo.VideoListItemVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 视频列表二级缓存（本地 Caffeine + Redis）与 SWR 逻辑的单测。
 * 覆盖：本地命中不再查 Redis、软过期 stale 判定、返回副本不污染缓存、SWR 同 key 去重。
 */
@ExtendWith(MockitoExtension.class)
class VideoListCacheServiceImplTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private VideoListCacheServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VideoListCacheServiceImpl(
                redisTemplate,
                stringRedisTemplate,
                new SimpleMeterRegistry()
        );
        // 软过期 60s、硬过期 60s、上限 200
        ReflectionTestUtils.setField(service, "ttlSeconds", 60L);
        ReflectionTestUtils.setField(service, "localMaxSize", 200);
        ReflectionTestUtils.setField(service, "localSoftTtlSeconds", 60L);
        ReflectionTestUtils.setField(service, "localHardTtlSeconds", 60L);
        // lenient：refreshAsync 等测试中 loader 被卡住不触发 putPage，相关 stub 不会被使用
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
    }

    private PageResult<VideoListItemVO> sample() {
        VideoListItemVO v = new VideoListItemVO();
        v.setId(1L);
        v.setTitle("原始标题");
        v.setCoverUrl("cover/a.jpg");
        return new PageResult<>(new ArrayList<>(List.of(v)), 100L, 1L, 12L, 9L);
    }

    @Test
    void putThenGetHitsLocalCacheWithoutTouchingRedis() {
        service.putPage(null, 1L, 12L, sample());

        VideoListCacheService.CacheLookup first = service.getPage(null, 1L, 12L);
        assertNotNull(first);
        assertFalse(first.stale(), "软过期未到，不应标记为 stale");
        assertEquals(1L, first.data().records().get(0).getId());

        service.getPage(null, 1L, 12L);
        // putPage 后两次 getPage 全部命中本地缓存，Redis 一次都不应该被查询
        verify(valueOperations, never()).get(anyString());
    }

    @Test
    void softExpiredEntryIsReturnedAsStale() {
        // 软过期设为 0：任何数据都会立即判定为 stale（SWR 触发分支）
        ReflectionTestUtils.setField(service, "localSoftTtlSeconds", 0L);
        service.putPage(null, 2L, 12L, sample());

        VideoListCacheService.CacheLookup lookup = service.getPage(null, 2L, 12L);
        assertNotNull(lookup);
        assertTrue(lookup.stale(), "超过软过期后应标记为 stale，由上层触发异步刷新");
        // stale 数据仍可正常返回（SWR 先用旧值）
        assertEquals("原始标题", lookup.data().records().get(0).getTitle());
    }

    @Test
    void returnedRecordsAreCopiesAndDoNotPolluteCache() {
        service.putPage(null, 3L, 12L, sample());

        VideoListCacheService.CacheLookup lookup = service.getPage(null, 3L, 12L);
        lookup.data().records().get(0).setTitle("被篡改");

        VideoListCacheService.CacheLookup again = service.getPage(null, 3L, 12L);
        assertEquals("原始标题", again.data().records().get(0).getTitle(),
                "上层修改返回结果不应污染缓存中的原始数据");
    }

    @Test
    void refreshAsyncDeduplicatesSameKey() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<PageResult<VideoListItemVO>> blockingLoader = () -> {
            calls.incrementAndGet();
            firstStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return sample();
        };

        // 第一个刷新任务进入执行（被 latch 卡住，尚未结束）
        service.refreshAsync(null, 1L, 12L, blockingLoader);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "第一个刷新任务应已开始执行");

        // 第二个刷新任务同一 key，应被去重，不执行 loader（若执行 calls 会变成 2）
        service.refreshAsync(null, 1L, 12L, () -> {
            calls.incrementAndGet();
            return null;
        });

        assertEquals(1, calls.get(), "同一 key 的并发刷新应只执行一次");
        release.countDown();
    }
}
