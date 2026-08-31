package com.videonest.module.upload.service;

import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 清理 complete 成功但用户始终没有提交投稿的正式对象。 */
@Service
@Slf4j
public class UploadOrphanCleanupService {
    private final StringRedisTemplate redisTemplate;
    private final MinioService minioService;

    @Value("${upload-cleanup.batch-size:100}")
    private int batchSize;

    @Value("${upload-cleanup.lock-seconds:900}")
    private long lockSeconds;

    public UploadOrphanCleanupService(StringRedisTemplate redisTemplate, MinioService minioService) {
        this.redisTemplate = redisTemplate;
        this.minioService = minioService;
    }

    @Scheduled(fixedDelayString = "${upload-cleanup.fixed-delay-milliseconds:600000}")
    public void cleanupExpiredConfirmedObjects() {
        String token = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                RedisKeys.UPLOAD_ORPHAN_CLEANUP_LOCK,
                token,
                Math.max(lockSeconds, 60),
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(locked)) return;

        try {
            Set<String> indexed = redisTemplate.opsForSet().members(
                    RedisKeys.UPLOAD_CONFIRMED_INDEX_KEY
            );
            if (indexed == null || indexed.isEmpty()) return;

            int processed = 0;
            for (String objectName : indexed) {
                if (processed++ >= Math.max(batchSize, 1)) break;
                // 已投稿对象仍有确认标记直到 afterCommit 消费；只有标记过期/被删除才允许清理。
                if (Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.confirmedUpload(objectName)))) {
                    continue;
                }
                try {
                    minioService.deleteObject(objectName);
                    redisTemplate.opsForSet().remove(
                            RedisKeys.UPLOAD_CONFIRMED_INDEX_KEY,
                            objectName
                    );
                    log.info("清理未提交上传对象成功，objectName={}", objectName);
                } catch (RuntimeException e) {
                    // 删除失败保留索引，等待下一轮重试。
                    log.warn("清理未提交上传对象失败，objectName={}", objectName, e);
                }
            }
        } finally {
            // 仅删除自己的锁，避免误删其他实例刚获得的锁。
            String current = redisTemplate.opsForValue().get(RedisKeys.UPLOAD_ORPHAN_CLEANUP_LOCK);
            if (token.equals(current)) {
                redisTemplate.delete(RedisKeys.UPLOAD_ORPHAN_CLEANUP_LOCK);
            }
        }
    }
}
