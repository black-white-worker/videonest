package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.config.HotRankProperties;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.vo.HotVideoCardsCache;
import com.videonest.module.video.vo.VideoListItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 热门视频缓存服务实现类
 * 两套刷新机制：
 * 1.定时任务主动刷新refreshRankAndCards()：分布式锁保证集群只有一台机器执行热度计算，写Redis
 * 2.缓存缺失被动重建getHotVideos()：本地synchronized锁防止单机缓存击穿
 * 容错设计：Redis读写全部try‑catch，缓存失败降级走数据库；刷新失败保留旧缓存不影响业务
 */
@Service
@Slf4j
public class HotVideoCacheServiceImpl implements HotVideoCacheService {

    /**
     * Lua解锁脚本常量
     * KEYS[1]：分布式锁key
     * ARGV[1]：当前线程持有的锁token
     * 逻辑：只有当前token持有锁，才允许DEL删除锁；防止别的线程误释放锁
     * 返回：删除成功返回1，不执行删除返回0
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final HotRankService hotRankService;
    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final HotRankProperties properties;
    private final Object localRefreshMonitor = new Object();

    public HotVideoCacheServiceImpl(
            HotRankService hotRankService,
            VideoMapper videoMapper,
            MinioService minioService,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate,
            HotRankProperties properties
    ) {
        this.hotRankService = hotRankService;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    /**
     * 获取热门视频列表
     * 先读Redis缓存；缓存为空进入同步块；双重检测锁重建缓存
     * 正常请求直接命中缓存，只有缓存缺失才同步回源重建
     * @param limit 需要返回多少条
     * @return 视频VO列表
     */
    @Override
    public List<VideoListItemVO> getHotVideos(int limit) {
        if (limit <= 0) {
            return List.of();
        }

        // 安全读取缓存，捕获Redis异常，异常返回null
        List<VideoListItemVO> cached = readCardsSafely();
        if (cached != null) {
            return first(cached, limit);
        }

        // 缓存未命中，加JVM本地锁，单机防止大量线程同时重建缓存
        synchronized (localRefreshMonitor) {
            // 再次读缓存DCL：防止已经有其他线程重建完成
            cached = readCardsSafely();
            if (cached == null) {
                // 获取热榜TOP视频ID，最大缓存数量maxSize
                cached = rebuildCards(hotRankService.getTopVideoIds(
                        properties.getMaxSize()
                ));
                // 将重建完成的数据写入Redis缓存
                writeCardsSafely(cached);
            }
        }
        return first(cached, limit);
    }

    /**
     * 定时任务：刷新排行并更新缓存
     * @ Scheduled 注解：初始延迟、间隔时间从配置文件读取
     * 通过Redis分布式锁，保证多台服务器只有一台执行热榜聚合计算
     * fixedDelayString：上一次任务执行完毕后间隔N毫秒再跑下一次，不是固定频率
     */
    @Scheduled(
            initialDelayString = "${hot-rank.initial-delay-milliseconds:5000}",
            fixedDelayString = "${hot-rank.refresh-interval-milliseconds:45000}"
    )
    @Override
    public void refreshRankAndCards() {
        String token = UUID.randomUUID().toString();
        boolean locked;
        try {
            // 获取Redis分布式锁
            locked = Boolean.TRUE.equals(
                    stringRedisTemplate.opsForValue().setIfAbsent(
                            RedisKeys.VIDEO_HOT_REFRESH_LOCK,
                            token,
                            properties.getRefreshLockSeconds(),
                            TimeUnit.SECONDS
                    )
            );
        } catch (RuntimeException e) {
            log.warn("获取热榜刷新锁失败，本轮跳过", e);
            return;
        }
        if (!locked) {
            return;
        }

        try {
            // 执行热度聚合，刷新zset热榜，拿到最新排序的视频ID列表
            List<Long> videoIds = hotRankService.refreshCurrentRank();
            writeCardsSafely(rebuildCards(videoIds));
        } catch (RuntimeException e) {
            // 当前 ZSet 使用临时 Key 原子替换，刷新失败时旧缓存仍可继续服务。
            log.error("预聚合热榜失败，保留上一版缓存", e);
        } finally {
            unlockRefresh(token);
        }
    }

    /**
     * 主动失效热榜缓存：直接删除Redis里面热榜卡片key
     * 不会重建，下一次接口请求getHotVideos触发被动重建
     */
    @Override
    public void invalidateCards() {
        try {
            redisTemplate.delete(RedisKeys.VIDEO_HOT_CARDS_KEY);
        } catch (RuntimeException e) {
            // 60 秒 TTL 会兜底，缓存失效失败不应回滚视频业务事务。
            log.warn("主动失效热榜卡片缓存失败", e);
        }
    }

    /**
     * 根据视频ID列表组装视频VO列表，缓存重建核心私有方法
     * 1.如果传入videoIds为空，降级查询最近发布视频兜底
     * 2.根据id批量查询数据库，保证返回顺序和传入videoIds顺序完全一致（热榜顺序不能乱）
     * 3.调用MinIO转换封面地址
     * @param videoIds 热榜排序后的视频id
     * @return 组装完成可放入缓存的VO集合
     */
    private List<VideoListItemVO> rebuildCards(List<Long> videoIds) {
        List<VideoListItemVO> videos;
        if (videoIds == null || videoIds.isEmpty()) {
            videos = videoMapper.selectRecentPublished(properties.getMaxSize());
        } else {
            Map<Long, VideoListItemVO> videosById = new HashMap<>();
            for (VideoListItemVO video : videoMapper.selectPublishedListByIds(videoIds)) {
                videosById.put(video.getId(), video);
            }
            // 按传入videoIds顺序遍历，从map拿数据，保证热榜排序不变，过滤掉数据库不存在视频
            videos = videoIds.stream()
                    // 遍历每一个id，去Map里面get(id)拿VO对象
                    .map(videosById::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        List<VideoListItemVO> cards = new ArrayList<>(videos.size());
        for (VideoListItemVO video : videos) {
            // 将MinIO存储的对象key转换为前端可以直接访问http封面url
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            cards.add(video);
        }
        return cards;
    }

    /**
     * 安全读取缓存，捕获Redis运行时异常
     * 缓存读取异常返回null，上层会降级重建缓存
     * @return 缓存中的视频列表；缓存不存在/序列化异常返回null
     */
    private List<VideoListItemVO> readCardsSafely() {
        try {
            Object cached = redisTemplate.opsForValue().get(
                    RedisKeys.VIDEO_HOT_CARDS_KEY
            );
            if (cached instanceof HotVideoCardsCache cardsCache) {
                return cardsCache.videos();
            }
        } catch (RuntimeException e) {
            log.warn("读取热榜卡片缓存失败，将降级重建", e);
        }
        return null;
    }

    /**
     * 安全写入缓存，设置TTL过期时间
     * 包装一层HotVideoCardsCache存入Redis，捕获异常，写入失败只打日志，不抛异常
     * @param cards 需要存入缓存的视频VO
     */
    private void writeCardsSafely(List<VideoListItemVO> cards) {
        try {
            redisTemplate.opsForValue().set(
                    RedisKeys.VIDEO_HOT_CARDS_KEY,
                    // 复制一份集合副本封装进缓存对象，避免原集合在 Redis 序列化过程中被修改，保证写入缓存的数据稳定。
                    new HotVideoCardsCache(new ArrayList<>(cards)),
                    // TTL过期时间数值
                    properties.getCardsTtlSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (RuntimeException e) {
            log.warn("写入热榜卡片缓存失败", e);
        }
    }

    /**
     * 截取列表，做边界保护，返回不可修改List副本
     * @param cards 完整缓存列表
     * @param requestedLimit 用户请求条数
     * @return 截取之后的视频列表
     */
    private List<VideoListItemVO> first(
            List<VideoListItemVO> cards,
            int requestedLimit
    ) {
        int end = Math.min(
                Math.min(requestedLimit, properties.getMaxSize()),
                cards.size()
        );
        // subList截取 List，左闭右开区间，取 [0 , end)返回视图，
        // 使用List.copyOf生成只读副本，防止外部修改原集合
        return List.copyOf(cards.subList(0, end));
    }

    /**
     * 使用Lua脚本执行解锁，保证解锁原子性
     * @param token 当前锁的唯一标识
     */
    private void unlockRefresh(String token) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    List.of(RedisKeys.VIDEO_HOT_REFRESH_LOCK),
                    token
            );
        } catch (RuntimeException e) {
            log.warn("释放热榜刷新锁失败", e);
        }
    }
}
