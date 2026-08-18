package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.VideoViewCountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 视频播放计数服务实现类
 * 核心方案：Redis + Lua脚本实现播放去重、匿名限流、增量缓存；定时任务批量落库MySQL
 * 整体架构：
 * 1. 每次播放请求执行Lua脚本，完成去重、限流、内存计数，不直接写MySQL
 * 2. 维护dirty脏集合，记录有增量需要刷库的videoId
 * 3. @Scheduled定时调用flushPendingViews，批量把Redis增量更新到数据库
 * 4. 数据库更新失败时，执行restoreDeltas把增量放回Redis，保证数据不丢失
 */
@Service
@Slf4j
public class VideoViewCountServiceImpl implements VideoViewCountService {

    /** KEYS数组
     * KEYS[1]：videoViewDedup 去重key，{videoId}:{viewerKey}，NX设置标记用户已经看过该视频
     * KEYS[2]：anonymousViewRate 匿名用户分钟限流key，ipHash+时间窗口，统计一分钟访问次数
     * KEYS[3]：videoViewTotal redis内视频总播放数(持久基数+增量)
     * KEYS[4]：videoViewDelta 需要刷入数据库的增量值
     * KEYS[5]：VIDEO_VIEW_DIRTY_KEY 脏集合set，存放需要同步到mysql的videoId
     *
     * ARGV数组
     * ARGV[1]：persistedViewCount 数据库持久化的播放基数
     * ARGV[2]：videoId字符串
     * ARGV[3]：redisTtlSeconds redis总key过期时间
     * ARGV[4]：dedupWindowSeconds 去重窗口时间，同一个用户该时间内重复看不计播放
     * ARGV[5]：anonymous "1"=匿名用户 "0"=登录用户
     * ARGV[6]：anonymousLimitPerMinute 匿名每分钟最大播放上报次数
     * ARGV[7]：60 限流key过期时间60秒
     */

    /**
     * RECORD_VIEW_SCRIPT：记录播放行为的Lua脚本
     * 执行原子逻辑：匿名用户限流 -> 用户播放去重 -> 播放计数累加
     * 返回值约定：
     *  -1：匿名用户分钟访问超限，被限流
     *  负数(小于-1)：重复观看，绝对值-1为当前总播放数
     *  正数：本次播放有效，返回Redis中视频总播放数(DB持久值+redis增量)
     */
    private static final DefaultRedisScript<Long> RECORD_VIEW_SCRIPT =
            new DefaultRedisScript<>("""
                    if ARGV[5] == '1' then
                        -- 匿名访问计数器自增
                        local rate = redis.call('INCR', KEYS[2])
                        -- 计数器第一次创建，设置过期时间60秒
                        if rate == 1 then
                            redis.call('EXPIRE', KEYS[2], ARGV[7])
                        end
                        -- 超过每分钟匿名访问上限，返回-1代表限流
                        if rate > tonumber(ARGV[6]) then
                            return -1
                        end
                    end
                    -- SET NX：设置观看去重标记，EX设置去重窗口过期时间；NX仅key不存在才设置成功
                    -- 设置失败：代表这个viewerKey在去重窗口内已经看过该视频，属于重复播放
                    if not redis.call('SET', KEYS[1], '1', 'EX', ARGV[4], 'NX') then
                        local current = redis.call('GET', KEYS[3])
                        -- 如果redis没有总计数，使用数据库传入的持久基数
                        if not current then
                            current = ARGV[1]
                        end
                        -- 上层拿到负数就判定重复观看，取绝对值-1得到播放总数
                        return -tonumber(current) - 1
                    end
                    -- 如果videoViewTotal不存在，初始化值为数据库持久播放基数ARGV[1]
                    if redis.call('EXISTS', KEYS[3]) == 0 then
                        redis.call('SET', KEYS[3], ARGV[1])
                    end
                    local total = redis.call('INCRBY', KEYS[3], 1)
                    -- 需要刷库的增量delta +1（待同步mysql的增量）
                    redis.call('INCRBY', KEYS[4], 1)
                    -- 将videoId加入脏集合，标记该视频有增量需要后续flush刷数据库
                    redis.call('SADD', KEYS[5], ARGV[2])
                    -- 设置总计数key、增量delta key的过期时间
                    redis.call('EXPIRE', KEYS[3], ARGV[3])
                    redis.call('EXPIRE', KEYS[4], ARGV[3])
                    return total
                    """, Long.class);

    /**
     * CLAIM_DELTA_SCRIPT：抢占获取待同步增量Lua脚本
     * flush定时任务执行；原子取出delta，并且删除redis中的delta，从dirty集合移除videoId
     * 作用：抢占，防止多个定时实例并发刷库，避免同一份delta多次更新数据库
     * 返回：delta数值；0代表没有增量
     */
    private static final DefaultRedisScript<Long> CLAIM_DELTA_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = redis.call('GET', KEYS[1])
                    -- delta为空或者等于0，直接从脏集合移除videoId，返回0
                    if not delta or tonumber(delta) == 0 then
                        redis.call('SREM', KEYS[2], ARGV[1])
                        return 0
                    end
                    -- 原子删除delta key，把videoId从dirty集合移除
                    redis.call('DEL', KEYS[1])
                    redis.call('SREM', KEYS[2], ARGV[1])
                    return tonumber(delta)
                    """, Long.class);

    /**
     * RESTORE_DELTA_SCRIPT：恢复增量Lua脚本
     * 数据库批量更新异常失败的时候调用，把已经claim抢占走的delta放回Redis
     * 保证故障场景播放增量不会丢失；同时重新加入dirty集合，等待下一轮定时任务刷库
     */
    private static final DefaultRedisScript<Long> RESTORE_DELTA_SCRIPT =
            new DefaultRedisScript<>("""
                    local delta = redis.call('INCRBY', KEYS[1], ARGV[1])
                    -- videoId重新加入脏集合，下一次定时任务继续处理
                    redis.call('SADD', KEYS[2], ARGV[2])
                    redis.call('EXPIRE', KEYS[1], ARGV[3])
                    return delta
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final VideoMapper videoMapper;

    @Value("${video-view-count.batch-size:200}")
    private int batchSize;              // flush单次最多处理多少个视频，默认200

    @Value("${video-view-count.redis-ttl-seconds:604800}")
    private long redisTtlSeconds;       // redis计数key总过期时间

    @Value("${video-view-count.dedup-window-seconds:1800}")
    private long dedupWindowSeconds;    // 播放去重窗口，1800秒=30分钟，同一个用户30分钟内重复打开不计播放

    @Value("${video-view-count.anonymous-limit-per-minute:30}")
    private int anonymousLimitPerMinute;    // 匿名用户每分钟最多上报播放次数，防刷

    public VideoViewCountServiceImpl(
            StringRedisTemplate redisTemplate,
            VideoMapper videoMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.videoMapper = videoMapper;
    }

    /**
     * 记录播放
     * @param videoId 视频id
     * @param persistedViewCount mysql数据库持久化的播放基数
     * @param viewerKey 观看者唯一标识，登录用户userId，匿名生成cookie标识
     * @param ipHash ip哈希脱敏值
     * @param anonymous 是否匿名访客
     * @return ViewRecordResult 是否接受本次播放、当前总播放数
     */
    @Override
    public ViewRecordResult recordView(
            Long videoId,
            long persistedViewCount,
            String viewerKey,
            String ipHash,
            boolean anonymous
    ) {
        // 计算分钟时间窗口：当前时间戳 /60000，得到代表当前分钟的数字；用于匿名限流key
        long rateWindow = System.currentTimeMillis() / 60_000L;
        Long total = redisTemplate.execute(
                RECORD_VIEW_SCRIPT,
                List.of(
                        RedisKeys.videoViewDedup(videoId, viewerKey),
                        RedisKeys.anonymousViewRate(ipHash, rateWindow),
                        RedisKeys.videoViewTotal(videoId),
                        RedisKeys.videoViewDelta(videoId),
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY
                ),
                Long.toString(persistedViewCount),
                videoId.toString(),
                Long.toString(redisTtlSeconds),
                Long.toString(dedupWindowSeconds),
                anonymous ? "1" : "0",
                Integer.toString(anonymousLimitPerMinute),
                "60"
        );
        if (total == null) {
            throw new IllegalStateException("Redis did not return the video view count");
        }
        if (anonymous && total == -1L) {
            throw new BusinessException(429, "匿名播放上报过于频繁，请稍后再试");
        }
        if (total < 0) {
            return new ViewRecordResult(false, -total - 1);
        }
        // 播放有效
        return new ViewRecordResult(true, total);
    }

    /**
     * 定时任务，周期性将Redis积攒的播放增量批量刷入MySQL
     * fixedDelayString：上一次任务执行结束后，间隔flush-delay-milliseconds再执行下一次，默认10000ms=10秒
     * initialDelayString：项目启动后延迟10秒开始执行第一次任务
     */
    @Scheduled(
            fixedDelayString =
                    "${video-view-count.flush-delay-milliseconds:10000}",
            initialDelayString =
                    "${video-view-count.flush-delay-milliseconds:10000}"
    )
    @Override
    public void flushPendingViews() {
        // 从dirty脏集合随机取出最多batchSize个videoId，distinctRandomMembers不删除原集合元素
        Set<String> videoIds = redisTemplate.opsForSet()
                .distinctRandomMembers(
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY,
                        Math.max(1, batchSize)
                );
        if (videoIds == null || videoIds.isEmpty()) {
            return;
        }

        // 调用claimDeltas，原子抢占各个视频的增量delta
        Map<Long, Long> claimedDeltas = claimDeltas(videoIds);
        if (claimedDeltas.isEmpty()) {
            return;
        }

        try {
            // mybatis批量执行update，更新播放增量 +=delta
            videoMapper.increaseViewCounts(claimedDeltas);
            log.debug(
                    "Flushed video view counts, videos={}, views={}",
                    claimedDeltas.size(),
                    claimedDeltas.values().stream()
                            .mapToLong(Long::longValue)
                            .sum()
            );
        } catch (RuntimeException e) {
            // mysql批量更新抛出异常：把抢占拿到的delta全部回写回Redis，数据不丢失
            restoreDeltas(claimedDeltas);
            log.error(
                    "Failed to flush video view counts; increments were restored",
                    e
            );
        }
    }

    /**
     * claimDeltas：批量抢占视频播放增量
     * 循环每个videoId，执行CLAIM_DELTA_SCRIPT脚本，原子拿走delta，删除redis delta key
     * @param videoIds dirty集合拿到的videoId字符串集合
     * @return map key=videoId value=抢占到的播放增量delta
     */
    private Map<Long, Long> claimDeltas(Set<String> videoIds) {
        Map<Long, Long> claimedDeltas = new LinkedHashMap<>();
        for (String rawVideoId : videoIds) {
            try {
                Long videoId = Long.valueOf(rawVideoId);
                Long delta = redisTemplate.execute(
                        CLAIM_DELTA_SCRIPT,
                        List.of(
                                RedisKeys.videoViewDelta(videoId),
                                RedisKeys.VIDEO_VIEW_DIRTY_KEY
                        ),
                        rawVideoId
                );
                // delta>0代表抢占成功，有增量需要更新数据库
                if (delta != null && delta > 0) {
                    claimedDeltas.put(videoId, delta);
                }
            } catch (NumberFormatException e) {
                // 脏集合里面出现非法非数字videoId，直接从集合移除，打警告日志
                redisTemplate.opsForSet().remove(
                        RedisKeys.VIDEO_VIEW_DIRTY_KEY,
                        rawVideoId
                );
                log.warn("Removed invalid video id from dirty view set: {}", rawVideoId);
            }
        }
        return claimedDeltas;
    }

    /**
     * restoreDeltas：数据库刷库失败，把已经claim抢占的增量恢复回Redis
     * @param claimedDeltas claimDeltas拿到的videoId和对应的增量
     */
    private void restoreDeltas(Map<Long, Long> claimedDeltas) {
        List<RuntimeException> failures = new ArrayList<>();
        claimedDeltas.forEach((videoId, delta) -> {
            try {
                redisTemplate.execute(
                        RESTORE_DELTA_SCRIPT,
                        List.of(
                                RedisKeys.videoViewDelta(videoId),
                                RedisKeys.VIDEO_VIEW_DIRTY_KEY
                        ),
                        delta.toString(),
                        videoId.toString(),
                        Long.toString(redisTtlSeconds)
                );
            } catch (RuntimeException e) {
                // 单个视频恢复异常收集异常，不打断整体循环
                failures.add(e);
                log.error(
                        "Failed to restore view increment, videoId={}, delta={}",
                        videoId,
                        delta,
                        e
                );
            }
        });
        // 存在恢复失败的视频，打印错误日志告警
        if (!failures.isEmpty()) {
            log.error(
                    "Failed to restore {} video view-count increments",
                    failures.size()
            );
        }
    }
}
