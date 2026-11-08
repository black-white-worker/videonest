package com.videonest.module.video.service.impl;

import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.service.VideoListCacheService;
import com.videonest.module.video.service.VideoViewCountService;
import com.videonest.module.video.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】验证视频详情缓存未命中时的进程内合流行为。
 * 关注点：同一个 videoId 的并发穿透只回源一次数据库，
 * 其余线程复用回源结果，而不是各自打库（旧实现等 40 毫秒后全员查库）。
 */
@ExtendWith(MockitoExtension.class)
class VideoDiscoveryServiceImplDetailRebuildTest {

    private static final int CONCURRENCY = 32;

    @Mock
    private VideoMapper videoMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private HotRankService hotRankService;

    @Mock
    private HotVideoCacheService hotVideoCacheService;

    @Mock
    private VideoViewCountService videoViewCountService;

    @Mock
    private VideoListCacheService videoListCacheService;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private ValueOperations<String, String> lockValueOperations;

    /**
     * 缓存层始终未命中，逼所有请求走到回源分支。
     * @return 被测服务
     */
    private VideoDiscoveryServiceImpl service() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(lockValueOperations);
        when(lockValueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        return new VideoDiscoveryServiceImpl(
                videoMapper,
                minioService,
                hotRankService,
                hotVideoCacheService,
                videoViewCountService,
                videoListCacheService,
                redisTemplate,
                stringRedisTemplate
        );
    }

    private VideoDetailVO detail(Long videoId) {
        VideoDetailVO vo = new VideoDetailVO();
        vo.setId(videoId);
        vo.setTitle("并发回源测试");
        return vo;
    }

    /**
     * 并发发起同一视频的详情请求：回源期间故意拖慢数据库，
     * 旧实现下 31 个线程等满 40 毫秒后会全部查库，合流后只应有 1 次。
     */
    @Test
    void concurrentMissesOnSameVideoRebuildFromDatabaseOnce() throws Exception {
        long videoId = 42L;
        AtomicInteger queries = new AtomicInteger();
        when(videoMapper.selectPublishedDetailById(videoId)).thenAnswer(invocation -> {
            queries.incrementAndGet();
            Thread.sleep(200);
            return detail(videoId);
        });
        VideoDiscoveryServiceImpl service = service();

        List<Object> outcomes = runConcurrently(service, videoId);

        assertEquals(1, queries.get(), "同一个 videoId 的并发穿透只应回源一次");
        assertEquals(CONCURRENCY, outcomes.size());
        for (Object outcome : outcomes) {
            assertInstanceOf(VideoDetailVO.class, outcome);
            assertEquals(videoId, ((VideoDetailVO) outcome).getId());
        }
    }

    /**
     * 数据库里不存在该视频时，等待方也要复用同一次回源结果并拿到 404，
     * 不能因为空值占位而再各查一次库。
     */
    @Test
    void concurrentMissesOnMissingVideoRebuildOnceAndReturn404() throws Exception {
        long videoId = 99L;
        AtomicInteger queries = new AtomicInteger();
        when(videoMapper.selectPublishedDetailById(videoId)).thenAnswer(invocation -> {
            queries.incrementAndGet();
            Thread.sleep(200);
            return null;
        });
        VideoDiscoveryServiceImpl service = service();

        List<Object> outcomes = runConcurrently(service, videoId);

        assertEquals(1, queries.get(), "空值占位同样只应回源一次");
        assertEquals(CONCURRENCY, outcomes.size());
        for (Object outcome : outcomes) {
            BusinessException failure = assertInstanceOf(BusinessException.class, outcome);
            assertEquals(404, failure.getCode());
        }
    }

    /**
     * 让 CONCURRENCY 个线程在同一时刻冲进详情接口，返回每个线程的结果：
     * 正常返回的 VO 或抛出的异常对象。
     * @param service 被测服务
     * @param videoId 视频id
     * @return 每个线程的结果
     */
    private List<Object> runConcurrently(VideoDiscoveryServiceImpl service, long videoId)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CyclicBarrier startLine = new CyclicBarrier(CONCURRENCY);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENCY; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(5, TimeUnit.SECONDS);
                    try {
                        return service.getPublishedVideoDetail(videoId);
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(CONCURRENCY, outcomes.size());
            for (Object outcome : outcomes) {
                assertNotNull(outcome, "每个并发线程都应拿到结果");
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
