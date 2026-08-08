package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.infrastructure.mq.DelayedMessagePublisher;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.common.exception.VideoProcessingException;
import com.example.demo.module.video.config.VideoProcessProperties;
import com.example.demo.module.video.config.VideoReviewProperties;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.event.ReviewTimeoutEvent;
import com.example.demo.module.video.mapper.VideoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

@Service
@Slf4j
public class VideoProcessMessageConsumer {

    private static final long LIST_COVER_MAX_BYTES = 300L * 1024;
    private static final long DETAIL_COVER_MAX_BYTES = 800L * 1024;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final ObjectMapper objectMapper;
    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final VideoProcessProperties properties;
    private final VideoReviewProperties reviewProperties;
    private final DelayedMessagePublisher delayedMessagePublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final HotVideoCacheService hotVideoCacheService;
    private volatile boolean coverBackfillComplete;

    public VideoProcessMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            MinioService minioService,
            VideoProcessProperties properties,
            VideoReviewProperties reviewProperties,
            DelayedMessagePublisher delayedMessagePublisher,
            RedisTemplate<String, Object> redisTemplate,
            HotVideoCacheService hotVideoCacheService
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.properties = properties;
        this.reviewProperties = reviewProperties;
        this.delayedMessagePublisher = delayedMessagePublisher;
        this.redisTemplate = redisTemplate;
        this.hotVideoCacheService = hotVideoCacheService;
    }

    @RabbitListener(
            queues = RabbitMqConfig.VIDEO_PROCESS_QUEUE,
            concurrency = "${video-process.consumer-concurrency:2}"
    )
    public void consume(String message) {
        VideoProcessEvent event = readEvent(message);
        Video video = videoMapper.selectById(event.videoId());

        // 防止 RabbitMQ 重复投递时重复转码。
        if (video == null || !"PROCESSING".equals(video.getStatus())) {
            log.info("跳过重复或过期的视频处理消息，videoId={}", event.videoId());
            return;
        }

        String lockKey = RedisKeys.videoProcessLock(event.videoId());
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                properties.getTimeoutSeconds() + 300,
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(locked)) {
            log.info("视频处理任务正在由其他实例执行，videoId={}", event.videoId());
            return;
        }

        Path workDir = null;
        try {
            log.info("开始处理视频，videoId={}，source={}", event.videoId(), event.sourceObjectName());
            workDir = Files.createTempDirectory("videonest-" + event.videoId() + "-");
            Path source = workDir.resolve("source.mp4");
            try (InputStream inputStream = minioService.download(event.sourceObjectName())) {
                Files.copy(inputStream, source);
            }

            Path video480 = workDir.resolve("480p.mp4");
            Path video720 = workDir.resolve("720p.mp4");
            Path video1080 = workDir.resolve("1080p.mp4");
            Path coverSource = workDir.resolve("cover-source.jpg");
            Path coverList = workDir.resolve("cover-list-400.jpg");
            Path coverDetail = workDir.resolve("cover-detail-1080.jpg");

            transcode(
                    source,
                    video480,
                    new TranscodeProfile(480, 26, "1000k", "2000k", "96k")
            );
            transcode(
                    source,
                    video720,
                    new TranscodeProfile(720, 23, "2500k", "5000k", "128k")
            );
            transcode(
                    source,
                    video1080,
                    new TranscodeProfile(1080, 21, "5000k", "10000k", "160k")
            );
            prepareCoverSource(source, video, coverSource);
            generateCoverVariant(
                    coverSource, coverList, 400, LIST_COVER_MAX_BYTES
            );
            generateCoverVariant(
                    coverSource, coverDetail, 1080, DETAIL_COVER_MAX_BYTES
            );

            String basePath = "processed/" + video.getId();
            String video480Name = basePath + "/480p.mp4";
            String video720Name = basePath + "/720p.mp4";
            String video1080Name = basePath + "/1080p.mp4";
            String coverBasePath = "cover/processed/" + video.getId();
            String coverListName = coverBasePath + "/list-400.jpg";
            String coverDetailName = coverBasePath + "/detail-1080.jpg";

            minioService.uploadFile(video480, video480Name, "video/mp4");
            minioService.uploadFile(video720, video720Name, "video/mp4");
            minioService.uploadFile(video1080, video1080Name, "video/mp4");
            minioService.uploadFile(coverList, coverListName, "image/jpeg");
            minioService.uploadFile(coverDetail, coverDetailName, "image/jpeg");

            video.setVideo480pUrl(video480Name);
            video.setVideo720pUrl(video720Name);
            video.setVideo1080pUrl(video1080Name);
            video.setVideoUrl(video720Name);
            // cover_url 保留为兼容字段，但同样只指向处理后的详情缩略图。
            video.setCoverUrl(coverDetailName);
            video.setCoverListUrl(coverListName);
            video.setCoverDetailUrl(coverDetailName);
            video.setStatus("PENDING");
            video.setProcessError(null);
            video.setReviewDeadline(
                    LocalDateTime.now().plusNanos(
                            TimeUnit.MILLISECONDS.toNanos(
                                    reviewProperties.getTimeoutMilliseconds()
                            )
                    )
            );
            video.setReviewTimeoutNotified(0);
            delayedMessagePublisher.scheduleReviewTimeout(
                    new ReviewTimeoutEvent(video.getId()),
                    reviewProperties.getTimeoutMilliseconds()
            );
            videoMapper.updateById(video);
            log.info("视频处理成功并进入待审核状态，videoId={}", event.videoId());
        } catch (IOException e) {
            log.error("视频处理发生文件系统故障，videoId={}", event.videoId(), e);
            throw new VideoProcessingException(
                    "FILE_SYSTEM",
                    shortMessage(e),
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("视频处理线程被中断，videoId={}", event.videoId(), e);
            throw new VideoProcessingException(
                    "INTERRUPTED",
                    "视频处理线程被中断",
                    e
            );
        } finally {
            deleteDirectory(workDir);
            unlockVideoProcess(lockKey, lockToken, event.videoId());
        }
    }

    /**
     * 仅释放当前消费者持有的锁，避免锁超时并被其他实例重新获取后被误删。
     */
    private void unlockVideoProcess(String lockKey, String lockToken, Long videoId) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (RuntimeException e) {
            log.warn("释放视频处理锁失败，等待锁自动过期，videoId={}", videoId, e);
        }
    }

    private VideoProcessEvent readEvent(String message) {
        try {
            return objectMapper.readValue(message, VideoProcessEvent.class);
        } catch (JsonProcessingException e) {
            log.error("视频处理消息格式错误，payload={}", message, e);
            throw new MessageConversionException("视频处理消息格式错误", e);
        }
    }

    private void transcode(
            Path source,
            Path output,
            TranscodeProfile profile
    )
            throws IOException, InterruptedException {
        runFfmpeg(List.of(
                properties.getFfmpegPath(), "-y", "-i", source.toString(),
                // 不放大低分辨率源视频，并确保 yuv420p 所需的宽高均为偶数。
                "-vf", "scale=-2:trunc(min(" + profile.height()
                        + "\\,ih)/2)*2",
                "-map", "0:v:0", "-map", "0:a?",
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-threads", "1",
                "-preset", "medium",
                "-crf", Integer.toString(profile.crf()),
                "-maxrate", profile.maxRate(),
                "-bufsize", profile.bufferSize(),
                "-c:a", "aac",
                "-b:a", profile.audioBitrate(),
                "-movflags", "+faststart",
                output.toString()
        ));
    }

    private record TranscodeProfile(
            int height,
            int crf,
            String maxRate,
            String bufferSize,
            String audioBitrate
    ) {
    }

    private void generateCover(Path source, Path cover)
            throws IOException, InterruptedException {
        runFfmpeg(List.of(
                properties.getFfmpegPath(), "-y", "-ss", "00:00:01",
                "-i", source.toString(), "-frames:v", "1", "-q:v", "2",
                cover.toString()
        ));
    }

    private void prepareCoverSource(Path videoSource, Video video, Path coverSource)
            throws IOException, InterruptedException {
        String originalCover = StringUtils.hasText(video.getOriginalCoverUrl())
                ? video.getOriginalCoverUrl()
                : legacyOriginalCover(video.getCoverUrl());
        if (StringUtils.hasText(originalCover)) {
            try (InputStream inputStream = minioService.download(originalCover)) {
                Files.copy(inputStream, coverSource);
            }
            return;
        }
        generateCover(videoSource, coverSource);
    }

    private String legacyOriginalCover(String coverObjectName) {
        if (!StringUtils.hasText(coverObjectName)
                || coverObjectName.startsWith("http://")
                || coverObjectName.startsWith("https://")
                || coverObjectName.startsWith("cover/processed/")) {
            return null;
        }
        return coverObjectName;
    }

    private void generateCoverVariant(
            Path source,
            Path output,
            int maxWidth,
            long maxBytes
    ) throws IOException, InterruptedException {
        int quality = 3;
        do {
            runFfmpeg(List.of(
                    properties.getFfmpegPath(), "-y", "-i", source.toString(),
                    "-frames:v", "1", "-an", "-map_metadata", "-1",
                    "-vf", "scale=min(" + maxWidth + "\\,iw):-2",
                    "-q:v", Integer.toString(quality),
                    output.toString()
            ));
            if (Files.size(output) <= maxBytes) {
                return;
            }
            quality += 2;
        } while (quality <= 15);

        throw new VideoProcessingException(
                "COVER_SIZE",
                "封面缩略图压缩后仍超过 " + maxBytes + " 字节"
        );
    }

    /**
     * 为升级前已经入库的用户封面补齐缩略图。批量任务只读取私有原图，
     * 生成结果仍写入公开的 processed 前缀；多实例重复执行也是幂等的。
     */
    @Scheduled(
            initialDelayString = "${video-process.cover-backfill-initial-delay-milliseconds:60000}",
            fixedDelayString = "${video-process.cover-backfill-fixed-delay-milliseconds:300000}"
    )
    public void backfillLegacyCoverThumbnails() {
        if (coverBackfillComplete) {
            return;
        }
        List<Video> videos = videoMapper.selectCoverThumbnailBackfillBatch(10);
        if (videos.isEmpty()) {
            coverBackfillComplete = true;
            log.info("历史封面缩略图补齐完成");
            return;
        }
        for (Video video : videos) {
            String originalCover = StringUtils.hasText(video.getOriginalCoverUrl())
                    ? video.getOriginalCoverUrl()
                    : legacyOriginalCover(video.getCoverUrl());
            if (!StringUtils.hasText(originalCover)) {
                continue;
            }

            Path workDir = null;
            try {
                workDir = Files.createTempDirectory(
                        "videonest-cover-backfill-" + video.getId() + "-"
                );
                Path source = workDir.resolve("cover-source.jpg");
                Path listCover = workDir.resolve("list-400.jpg");
                Path detailCover = workDir.resolve("detail-1080.jpg");
                try (InputStream inputStream = minioService.download(originalCover)) {
                    Files.copy(inputStream, source);
                }
                generateCoverVariant(source, listCover, 400, LIST_COVER_MAX_BYTES);
                generateCoverVariant(source, detailCover, 1080, DETAIL_COVER_MAX_BYTES);

                String basePath = "cover/processed/" + video.getId();
                String listObjectName = basePath + "/list-400.jpg";
                String detailObjectName = basePath + "/detail-1080.jpg";
                minioService.uploadFile(listCover, listObjectName, "image/jpeg");
                minioService.uploadFile(detailCover, detailObjectName, "image/jpeg");

                Video update = new Video();
                update.setId(video.getId());
                update.setOriginalCoverUrl(originalCover);
                update.setCoverUrl(detailObjectName);
                update.setCoverListUrl(listObjectName);
                update.setCoverDetailUrl(detailObjectName);
                videoMapper.updateById(update);
                redisTemplate.delete(RedisKeys.videoDetail(video.getId()));
                hotVideoCacheService.invalidateCards();
                log.info("历史封面缩略图补齐成功，videoId={}", video.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("历史封面缩略图补齐任务被中断，videoId={}", video.getId());
                return;
            } catch (Exception e) {
                log.error("历史封面缩略图补齐失败，videoId={}", video.getId(), e);
            } finally {
                deleteDirectory(workDir);
            }
        }
    }

    private void runFfmpeg(List<String> command)
            throws IOException, InterruptedException {
        Path logFile = Files.createTempFile(
                "videonest-ffmpeg-" + UUID.randomUUID(),
                ".log"
        );
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        boolean completed = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new VideoProcessingException("TIMEOUT", "FFmpeg 处理超时");
        }
        if (process.exitValue() != 0) {
            String output = Files.readString(logFile, StandardCharsets.UTF_8);
            // 数据库只保存摘要，完整命令和日志保留在服务端，便于定位具体素材问题。
            log.error("FFmpeg 执行失败，命令：{}，完整输出：{}", command, output);
            int start = Math.max(0, output.length() - 1600);
            throw new VideoProcessingException(
                    "FFMPEG_EXIT",
                    "FFmpeg 执行失败，退出码：" + process.exitValue()
                            + "，错误：" + output.substring(start)
            );
        }

        Files.deleteIfExists(logFile);
    }

    private String shortMessage(Throwable e) {
        String message = e.getMessage() == null ? "视频处理失败" : e.getMessage();
        return message.length() > 900
                ? message.substring(message.length() - 900)
                : message;
    }

    private void deleteDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("清理视频处理临时文件失败，path={}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("遍历视频处理临时目录失败，directory={}", directory, e);
        }
    }
}
