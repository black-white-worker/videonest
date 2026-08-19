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
import com.videonest.module.video.service.VideoListCacheService;
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

/**
 * C端用户视频浏览、搜索、视频详情、播放上报、热门视频查询
 * 1. Redis缓存视频详情 + 分布式锁解决缓存击穿
 * 2. 缓存空值（NULL_VIDEO）解决缓存穿透
 * 3. Lua脚本安全释放分布式锁，防止锁误删
 * 4. TTL随机抖动，避免缓存同时过期雪崩
 * 5. MinIO生成临时访问URL，不在Redis缓存临时url
 * 6. 播放计数交给VideoViewCountService做Redis去重，不直接写DB
 * 7. Redis读写全部try‑catch降级，Redis故障直接走数据库，服务不挂
 */
@Service
@Slf4j
public class VideoDiscoveryServiceImpl implements VideoDiscoveryService {


    private static final String NULL_VIDEO = "__NULL_VIDEO__";

    /**
     * Redis Lua解锁脚本
     * 防止A线程锁超时，B线程拿到锁，A线程把B线程锁删掉的问题
     * KEYS[1]：锁key
     * ARGV[1]：当前线程锁token
     * 只有当前锁的值等于本线程token，才执行DEL删除锁；否则直接返回0，不操作
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
    private final HotRankService hotRankService;
    private final HotVideoCacheService hotVideoCacheService;
    private final VideoViewCountService videoViewCountService;
    private final VideoListCacheService videoListCacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final Object firstPageRebuildMonitor = new Object();

    public VideoDiscoveryServiceImpl(
            VideoMapper videoMapper,
            MinioService minioService,
            HotRankService hotRankService,
            HotVideoCacheService hotVideoCacheService,
            VideoViewCountService videoViewCountService,
            VideoListCacheService videoListCacheService,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.hotRankService = hotRankService;
        this.hotVideoCacheService = hotVideoCacheService;
        this.videoViewCountService = videoViewCountService;
        this.videoListCacheService = videoListCacheService;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 分页查询已发布视频列表，支持分类筛选、关键词搜索
     * @param categoryId 分类ID
     * @param keyword 搜索关键词
     * @param page 页码
     * @param size 每页条数
     */
    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId, String keyword, long page, long size
    ) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        boolean cacheable = page == 1 && normalizedKeyword == null;
        if (cacheable) {
            PageResult<VideoListItemVO> cached = videoListCacheService.getFirstPage(
                    categoryId, size
            );
            if (cached != null) {
                return cached;
            }
            synchronized (firstPageRebuildMonitor) {
                cached = videoListCacheService.getFirstPage(categoryId, size);
                if (cached != null) {
                    return cached;
                }
                PageResult<VideoListItemVO> rebuilt = queryPublishedVideos(
                        categoryId, null, page, size
                );
                videoListCacheService.putFirstPage(categoryId, size, rebuilt);
                return rebuilt;
            }
        }

        return queryPublishedVideos(categoryId, normalizedKeyword, page, size);
    }

    private PageResult<VideoListItemVO> queryPublishedVideos(
            Long categoryId, String keyword, long page, long size
    ) {
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);
        pageRequest.setSearchCount(false);
        long total = videoMapper.countPublishedVideos(categoryId, keyword);
        IPage<VideoListItemVO> pageData = videoMapper.selectPublishedPage(
                pageRequest,
                categoryId,
                keyword
        );
        pageData.setTotal(total);
        // 遍历列表，把数据库存储的MinIO对象名，转换为带签名的临时访问url
        pageData.getRecords().forEach(video ->
                video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()))
        );
        PageResult<VideoListItemVO> result = PageResult.of(pageData);
        return result;
    }

    /**
     * 获取已发布视频详情，
     * @param videoId 视频主键ID
     * @return VideoDetailVO 视频详情
     */
    @Override
    public VideoDetailVO getPublishedVideoDetail(Long videoId) {
        String cacheKey = RedisKeys.videoDetail(videoId);
        // 带分布式锁逻辑获取缓存/回源数据库
        VideoDetailVO videoDetail = getCachedVideoDetail(videoId, cacheKey);

        // 填充各清晰度视频文件字节大小；发生数据变更时，重新写回Redis缓存
        if (populateVideoObjectSizes(videoDetail)) {
            setCacheSafely(cacheKey, videoDetail, cacheTtlMinutes(), TimeUnit.MINUTES);
        }

        // 对象拷贝，避免直接返回缓存中的对象，防止上层修改缓存对象
        VideoDetailVO response = new VideoDetailVO();
        BeanUtils.copyProperties(videoDetail, response);

        // 实时生成所有资源临时访问的URL预签名，防止url过期就不从缓存拿
        response.setCoverUrl(minioService.getAccessUrl(response.getCoverUrl()));
        response.setVideoUrl(minioService.getAccessUrl(response.getVideoUrl()));
        response.setVideo480pUrl(minioService.getAccessUrl(response.getVideo480pUrl()));
        response.setVideo720pUrl(minioService.getAccessUrl(response.getVideo720pUrl()));
        response.setVideo1080pUrl(minioService.getAccessUrl(response.getVideo1080pUrl()));
        return response;
    }

    /**
     * 记录播放行为
     * @param videoId 视频ID
     * @param viewerKey 观看者唯一标识
     * @param ipHash ip哈希值
     * @param anonymous 是否匿名用户
     * @return VideoViewReportVO 上报结果
     */
    @Override
    public VideoViewReportVO recordView(
            Long videoId, String viewerKey, String ipHash, boolean anonymous
    ) {
        // Redis集合做播放去重，判断本次播放是否有效
        VideoViewCountService.ViewRecordResult result = videoViewCountService.recordView(
                videoId, viewerKey, ipHash, anonymous
        );
        // 如果是有效播放，给视频增加热度分数，用于热门榜单计算
        if (result.accepted()) {
            hotRankService.addPlayScore(videoId);
        }
        return new VideoViewReportVO(result.accepted(), result.viewCount());
    }

    /**
     * 获取热门视频列表，直接调用缓存服务
     * @param limit 返回条数
     * @return 热门视频VO列表
     */
    @Override
    public List<VideoListItemVO> listHotVideos(int limit) {
        return hotVideoCacheService.getHotVideos(limit);
    }

    /**
     * 获取视频详情
     * 1.先读缓存；命中直接返回
     * 2.命中NULL_VIDEO，抛404
     * 3.未命中，抢Redis分布式锁
     * 4.抢到锁：再次查缓存，防止其他线程已经回填；没命中就查库写缓存
     * 5.没抢到锁：sleep短暂时间，再次尝试读缓存，降级不阻塞
     * @param videoId 视频id
     * @param cacheKey 缓存key
     * @return VideoDetailVO
     */
    private VideoDetailVO getCachedVideoDetail(Long videoId, String cacheKey) {
        Object cached = getCacheSafely(cacheKey);
        if (cached instanceof VideoDetailVO detail) {
            return detail;
        }
        // 命中空值占位，说明数据库不存在这个视频，缓存穿透防护
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
                // 没拿到锁，短暂等待，再读一次缓存；不无限阻塞
                Thread.sleep(40);
                afterWait = getCacheSafely(cacheKey);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("视频详情缓存不可用，降级查询数据库，videoId={}", videoId, e);
        }

        // 等待后缓存命中，释放锁直接返回
        if (afterWait instanceof VideoDetailVO detail) {
            unlockVideoDetail(lockKey, lockToken, videoId, locked);
            return detail;
        }

        // 等待后命中空占位，抛404
        if (NULL_VIDEO.equals(afterWait)) {
            unlockVideoDetail(lockKey, lockToken, videoId, locked);
            throw new BusinessException(404, "视频不存在");
        }

        // 没有命中缓存，查询数据库，写入缓存
        try {
            VideoDetailVO detail = videoMapper.selectPublishedDetailById(videoId);
            // 数据库不存在，写入空值占位
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

    /**
     * 使用Lua脚本安全释放分布式锁
     * @param lockKey 锁key
     * @param lockToken 当前线程的uuid令牌
     * @param videoId 视频id，日志打印用
     * @param locked 是否拿到锁，没拿到就不执行解锁
     */
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

    /**
     * 安全读取Redis缓存：捕获Redis异常，Redis挂掉不抛异常，返回null降级走数据库
     * @param key redis key
     * @return 缓存对象，异常返回null
     */
    private Object getCacheSafely(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取缓存失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 安全写入Redis缓存：捕获Redis异常，写缓存失败只打日志，业务流程继续往下走
     * @param key redis key
     * @param value 写入值
     * @param ttl 过期时间
     * @param unit 时间单位
     */
    private void setCacheSafely(String key, Object value, long ttl, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl, unit);
        } catch (RuntimeException e) {
            log.warn("写入缓存失败，key={}", key, e);
        }
    }

    /**
     * 获取随机TTL，25‑36分钟之间随机
     * 目的：缓存雪崩防护，大量视频缓存不会同一时刻集体过期
     * @return 随机分钟数
     */
    private long cacheTtlMinutes() {
        return ThreadLocalRandom.current().nextLong(25, 36);
    }

    /**
     * 填充各个清晰度视频文件字节大小，从MinIO读取object size
     * 只当VO里面size字段为空的时候才去拉取；拉取到之后返回true，触发重新写缓存
     * @param videoDetail 视频详情VO
     * @return true：size发生变更，需要更新缓存；false无需更新缓存
     */
    private boolean populateVideoObjectSizes(VideoDetailVO videoDetail) {
        boolean changed = false;
        // 分别拿路径对应的 MinIO 文件的字节大小填充null
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

    /**
     * 安全获取MinIO文件大小，捕获异常，MinIO故障返回null，不阻断主流程
     * @param objectName MinIO对象名
     * @return 文件字节大小；异常返回null
     */
    private Long getObjectSizeSafely(String objectName) {
        try {
            return minioService.getObjectSize(objectName);
        } catch (RuntimeException e) {
            log.warn("获取视频对象大小失败，objectName={}", objectName, e);
            return null;
        }
    }
}
