package com.videonest.module.video.service.impl;

import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.video.config.HotRankProperties;
import com.videonest.module.video.service.HotRankService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class HotRankServiceImpl implements HotRankService {

    // 时间桶格式化
    private static final DateTimeFormatter BUCKET_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final StringRedisTemplate stringRedisTemplate;
    private final HotRankProperties properties;

    public HotRankServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            HotRankProperties properties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    //播放 +1分
    @Override
    public void addPlayScore(Long videoId) {
        addScore(videoId, 1D);
    }

    //点赞 +5
    @Override
    public void addLikeScore(Long videoId) {
        addScore(videoId, 5D);
    }

    //收藏 +10
    @Override
    public void addFavoriteScore(Long videoId) {
        addScore(videoId, 10D);
    }

    //评论 +8
    @Override
    public void addCommentScore(Long videoId) {
        addScore(videoId, 8D);
    }

    /**
     * g读取已经计算完成的当前热榜ZSet
     */
    @Override
    public List<Long> getTopVideoIds(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }

        try {
            // 从大到小取 [0 ~ limit‑1]，分数越高越靠前
            Set<String> videoIds = stringRedisTemplate.opsForZSet()
                    .reverseRange(
                            RedisKeys.VIDEO_HOT_CURRENT_KEY,
                            0,
                            Math.min(limit, properties.getMaxSize()) - 1L
                    );
            if (videoIds == null || videoIds.isEmpty()) {
                return Collections.emptyList();
            }
            //字符串id转Long，过滤非法id
            return videoIds.stream()
                    .map(this::parseVideoId)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            //Redis异常直接返回空列表，上层会触发被动重建缓存降级
            log.warn("读取预聚合热榜失败，将由上层降级", e);
            return Collections.emptyList();
        }
    }

    /**
     * 定时任务调用
     * 1.遍历最近N个小时分桶
     * 2.对每一个桶应用指数衰减权重，旧桶权重变小
     * 3.内存汇总全部视频加权总分
     * 4.排序，生成新热榜，原子替换Redis当前热榜key
     */
    @Override
    public List<Long> refreshCurrentRank() {
        //取当前小时，把时分秒清零
        LocalDateTime currentHour = LocalDateTime.now()
                .truncatedTo(ChronoUnit.HOURS);

        Map<Long, Double> decayedScores = new HashMap<>();
        int maxSize = properties.getMaxSize();
        //每个桶只取前N个候选视频
        int candidatesPerBucket = Math.max(maxSize * 5, 50);

        for (int age = 0; age < properties.getWindowHours(); age++) {
            // age=0 为当前小时；age=1上一小时；age越大代表越久远
            LocalDateTime bucketHour = currentHour.minusHours(age);
            // weight = exp( −ln2 * age / halfLifeHours )，halfLifeHours半衰期
            double weight = Math.exp(
                    -Math.log(2D) * age / properties.getHalfLifeHours()
            );

            /*
            * 读取 bucketHour 对应的小时分桶，取出该桶热度最高的 candidatesPerBucket 条数据，
            * 拿到每一条的视频 id 和对应的小时热度分数，封装到 tuples 集合中。
            * */
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().reverseRangeWithScores(
                            bucketKey(bucketHour),
                            0,
                            candidatesPerBucket - 1L
                    );
            if (tuples == null) {
                continue;
            }

            //把该桶每个视频，乘衰减权重，累加到内存map
            for (var tuple : tuples) {
                Long videoId = parseVideoId(tuple.getValue());
                if (videoId != null && tuple.getScore() != null) {
                    decayedScores.merge(
                            videoId,
                            tuple.getScore() * weight,
                            Double::sum
                    );
                }
            }
        }

        //内存中对全部视频按总分降序排序；分数相同按videoId升序做稳定排序
        List<Map.Entry<Long, Double>> ranking = decayedScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(
                        Comparator.reverseOrder()
                ).thenComparing(Map.Entry.comparingByKey()))
                .limit(maxSize)
                .toList();

        //原子替换Redis中的当前生效热榜
        replaceCurrentRank(ranking);
        return ranking.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * replaceCurrentRank：原子更新当前热榜
     * 先写临时tmp key，全部写完之后 rename覆盖正式key
     */
    private void replaceCurrentRank(List<Map.Entry<Long, Double>> ranking) {
        // 如果热榜 ranking 为空，删除 Redis 中旧的正式热榜 key，直接结束方法，清理旧脏数据同时避免无效 Redis 调用。
        if (ranking.isEmpty()) {
            stringRedisTemplate.delete(RedisKeys.VIDEO_HOT_CURRENT_KEY);
            return;
        }

        //生成唯一临时key，避免并发冲突
        String temporaryKey = RedisKeys.VIDEO_HOT_CURRENT_KEY
                + ":tmp:" + java.util.UUID.randomUUID();
        try {
            // 往临时ZSet写入全部新热榜数据
            for (Map.Entry<Long, Double> entry : ranking) {
                stringRedisTemplate.opsForZSet().add(
                        temporaryKey,
                        entry.getKey().toString(),
                        entry.getValue()
                );
            }
            // 设置临时key过期时间，防止异常残留垃圾key
            stringRedisTemplate.expire(
                    temporaryKey,
                    properties.getCurrentTtlSeconds(),
                    TimeUnit.SECONDS
            );
            // 原子重命名，直接覆盖正式key
            stringRedisTemplate.rename(
                    temporaryKey,
                    RedisKeys.VIDEO_HOT_CURRENT_KEY
            );
        } catch (RuntimeException e) {
            stringRedisTemplate.delete(temporaryKey);
            throw e;
        }
    }

    /**
     * 字符串转Long视频ID，捕获格式错误，打印警告返回null
     */
    private Long parseVideoId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            log.warn("忽略热榜中的非法视频 ID: {}", value);
            return null;
        }
    }

    /**
     * addScore：向【当前小时桶】ZSet增加热度分数
     * 每一次播放/点赞/收藏/评论调用；ZSet incrementScore 分数累加
     */
    private void addScore(Long videoId, double score) {
        if (videoId == null || videoId <= 0) {
            throw new IllegalArgumentException("videoId must be positive");
        }

        try {
            String key = bucketKey(LocalDateTime.now());
            // ZSet 分数自增
            stringRedisTemplate.opsForZSet().incrementScore(
                    key,
                    videoId.toString(),
                    score
            );
            // 多保留两个小时，覆盖边界时钟偏差；排名只读取最近 24 个桶。
            stringRedisTemplate.expire(
                    key,
                    properties.getWindowHours() + 2L,
                    TimeUnit.HOURS
            );
        } catch (RuntimeException e) {
            log.warn("写入热度桶失败，videoId={}，score={}", videoId, score, e);
        }
    }

    /**
     * bucketKey：生成小时分桶的Redis key
     */
    private String bucketKey(LocalDateTime time) {
        return RedisKeys.VIDEO_HOT_BUCKET_PREFIX
                + time.truncatedTo(ChronoUnit.HOURS).format(BUCKET_FORMAT);
    }
}
