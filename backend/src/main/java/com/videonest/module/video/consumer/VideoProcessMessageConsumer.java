package com.videonest.module.video.consumer;

import com.videonest.infrastructure.mq.RabbitMqConfig;
import com.videonest.infrastructure.mq.DelayedMessagePublisher;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.common.exception.VideoProcessingException;
import com.videonest.module.video.config.VideoProcessProperties;
import com.videonest.module.video.config.VideoReviewProperties;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.event.VideoProcessEvent;
import com.videonest.module.video.event.ReviewTimeoutEvent;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.service.VideoListCacheService;
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

/**
 * RabbitMQ 消费者类,就是VideoProcessEvent事件的消费者
 * 接收MQ视频转码消息，调用FFmpeg做多分辨率转码、封面生成，上传MinIO，更新视频状态；附带历史封面补齐定时任务
 * */
@Service
@Slf4j
public class VideoProcessMessageConsumer {

    // 列表页封面最大字节限制：300KB
    private static final long LIST_COVER_MAX_BYTES = 300L * 1024;
    // 详情页封面最大字节限制：800KB
    private static final long DETAIL_COVER_MAX_BYTES = 800L * 1024;
    /*
     * Redis Lua解锁脚本：安全释放分布式锁
     * 判断当前锁的value等于传入的lockToken才允许删除锁
     * 防止：A线程锁超时，B线程拿到锁，A执行del把B的锁删掉
     * 返回1解锁成功，0解锁失败
     */
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
    private final VideoListCacheService videoListCacheService;
    private volatile boolean coverBackfillComplete;

    public VideoProcessMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            MinioService minioService,
            VideoProcessProperties properties,
            VideoReviewProperties reviewProperties,
            DelayedMessagePublisher delayedMessagePublisher,
            RedisTemplate<String, Object> redisTemplate,
            HotVideoCacheService hotVideoCacheService,
            VideoListCacheService videoListCacheService
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.properties = properties;
        this.reviewProperties = reviewProperties;
        this.delayedMessagePublisher = delayedMessagePublisher;
        this.redisTemplate = redisTemplate;
        this.hotVideoCacheService = hotVideoCacheService;
        this.videoListCacheService = videoListCacheService;
    }

    /**
     * queues：监听视频转码队列 VIDEO_PROCESS_QUEUE
     * concurrency：并发消费线程数，配置文件读取，默认2个线程，同时处理多条转码消息
     * message：MQ接收到原始字符串消息（JSON字符串）
     */
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
        // 生成随机锁令牌，用于Lua解锁脚本，区分不同任务的锁
        String lockToken = UUID.randomUUID().toString();
        // setIfAbsent 分布式锁：key不存在才设置；设置过期时间=转码超时+300秒兜底
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                properties.getTimeoutSeconds() + 300,
                TimeUnit.SECONDS
        );
        // 获取锁失败，说明别的服务实例正在处理这个视频，直接返回，避免重复转码
        if (!Boolean.TRUE.equals(locked)) {
            log.info("视频处理任务正在由其他实例执行，videoId={}", event.videoId());
            return;
        }

        Path workDir = null;
        try {
            log.info("开始处理视频，videoId={}，source={}", event.videoId(), event.sourceObjectName());
            // 在本地磁盘创建临时工作目录，所有转码中间文件全部放这里
            workDir = Files.createTempDirectory("videonest-" + event.videoId() + "-");
            // 原始视频本地临时文件拼接路径 source.mp4
            Path source = workDir.resolve("source.mp4");
            // 从MinIO下载原始视频到本地临时文件
            try (InputStream inputStream = minioService.download(event.sourceObjectName())) {
                Files.copy(inputStream, source);
            }

            Path video480 = workDir.resolve("480p.mp4");
            Path video720 = workDir.resolve("720p.mp4");
            Path video1080 = workDir.resolve("1080p.mp4");
            Path coverSource = workDir.resolve("cover-source.jpg");
            Path coverList = workDir.resolve("cover-list-400.jpg");
            Path coverDetail = workDir.resolve("cover-detail-1080.jpg");

            /**
             * 拼装 FFmpeg 命令行参数列表
             * */
            // FFmpeg转码生成480P视频
            transcode(
                    source,
                    video480,
                    new TranscodeProfile(480, 26, "1000k", "2000k", "96k")
            );
            // FFmpeg转码生成720P视频
            transcode(
                    source,
                    video720,
                    new TranscodeProfile(720, 23, "2500k", "5000k", "128k")
            );
            // FFmpeg转码生成1080P视频
            transcode(
                    source,
                    video1080,
                    new TranscodeProfile(1080, 21, "5000k", "10000k", "160k")
            );
            // 准备封面源文件：优先用户上传封面，没有则从视频截取一帧
            prepareCoverSource(source, video, coverSource);
            // 生成列表页封面缩略图
            generateCoverVariant(
                    coverSource, coverList, 400, LIST_COVER_MAX_BYTES
            );
            // 生成详情页封面缩略图
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

            // 将本地转码完成的文件上传MinIO对象存储
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
            // 设置审核截止时间
            video.setReviewDeadline(
                    LocalDateTime.now().plusNanos(
                            TimeUnit.MILLISECONDS.toNanos(
                                    reviewProperties.getTimeoutMilliseconds()
                            )
                    )
            );
            video.setReviewTimeoutNotified(0);
            // 发送RabbitMQ延迟消息，审核超时事件
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
            // finally块：无论成功失败，删除本地临时目录，释放磁盘空间
            deleteDirectory(workDir);
            // 释放redis分布式锁
            unlockVideoProcess(lockKey, lockToken, event.videoId());
        }
    }

    /**
     * 仅释放当前消费者持有的锁，避免锁超时并被其他实例重新获取后被误删。
     */
    private void unlockVideoProcess(String lockKey, String lockToken, Long videoId) {
        try {
            // 执行释放锁的lua脚本
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (RuntimeException e) {
            log.warn("释放视频处理锁失败，等待锁自动过期，videoId={}", videoId, e);
        }
    }

    /**
     * 将MQ收到的字符串消息反序列化为VideoProcessEvent事件对象
     * @param message MQ原始json字符串
     * @return VideoProcessEvent事件对象
     */
    private VideoProcessEvent readEvent(String message) {
        try {
            return objectMapper.readValue(message, VideoProcessEvent.class);
        } catch (JsonProcessingException e) {
            log.error("视频处理消息格式错误，payload={}", message, e);
            throw new MessageConversionException("视频处理消息格式错误", e);
        }
    }

    /**
     * FFmpeg视频转码方法，调用本地ffmpeg命令，输出不同分辨率视频文件
     * @param source 源视频本地路径
     * @param output 输出文件路径
     * @param profile 转码参数配置记录
     */
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

    /**
     * record记录：转码参数实体，只读，存放高度、crf画质系数、码率、音频码率
     * @param height 输出视频高度
     * @param crf h264画质系数
     * @param maxRate 最大码率
     * @param bufferSize 缓冲区大小
     * @param audioBitrate 音频码率
     */
    private record TranscodeProfile(
            int height,
            int crf,
            String maxRate,
            String bufferSize,
            String audioBitrate
    ) {
    }

    /**
     * FFmpeg截取视频第1帧，生成封面源图片
     * @param source 视频源文件路径
     * @param cover 输出封面图片路径
     */
    private void generateCover(Path source, Path cover)
            throws IOException, InterruptedException {
        runFfmpeg(List.of(
                properties.getFfmpegPath(), "-y", "-ss", "00:00:01",
                "-i", source.toString(), "-frames:v", "1", "-q:v", "2",
                cover.toString()
        ));
    }

    /**
     * 获取封面源文件：优先取用户上传封面；没有则从视频截取帧
     * @param videoSource 本地视频文件路径
     * @param video 数据库视频实体
     * @param coverSource 输出封面源文件本地路径
     */
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

    /**
     * 兼容老版本数据，判断旧coverUrl是否为原始对象存储路径；http/https、processed前缀直接返回null
     * @param coverObjectName 旧coverUrl字段
     * @return 原始封面对象名，或者null
     */
    private String legacyOriginalCover(String coverObjectName) {
        if (!StringUtils.hasText(coverObjectName)
                || coverObjectName.startsWith("http://")
                || coverObjectName.startsWith("https://")
                || coverObjectName.startsWith("cover/processed/")) {
            return null;
        }
        return coverObjectName;
    }

    /**
     * 循环降低图片质量，生成指定宽度、大小限制的封面缩略图
     * @param source 封面原图本地路径
     * @param output 输出缩略图路径
     * @param maxWidth 最大宽度
     * @param maxBytes 文件最大字节上限
     */
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
            quality += 2;   // 文件过大，降低画质，增大q:v数值
        } while (quality <= 15);        // 最大循环到15，如果还超限抛出异常

        throw new VideoProcessingException(
                "COVER_SIZE",
                "封面缩略图压缩后仍超过 " + maxBytes + " 字节"
        );
    }

    /**
     * 为升级前已经入库的用户封面补齐缩略图。批量任务只读取私有原图，
     * 生成结果仍写入公开的 processed 前缀；多实例重复执行也是幂等的。
     * 定时任务：启动延迟60s，每5分钟执行一次；补齐历史老视频的list/detail双封面
     */
    @Scheduled(
            initialDelayString = "${video-process.cover-backfill-initial-delay-milliseconds:60000}",
            fixedDelayString = "${video-process.cover-backfill-fixed-delay-milliseconds:300000}"
    )
    public void backfillLegacyCoverThumbnails() {
        if (coverBackfillComplete) {
            return;
        }
        // 查询一批待补齐封面的视频，一次最多10条
        List<Video> videos = videoMapper.selectCoverThumbnailBackfillBatch(10);
        if (videos.isEmpty()) {
            coverBackfillComplete = true;
            log.info("历史封面缩略图补齐完成");
            return;
        }
        // 遍历这批视频，逐个补齐封面
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
                // MinIO下载原始封面图
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

                // 构建更新对象，更新数据库封面字
                Video update = new Video();
                update.setId(video.getId());
                update.setOriginalCoverUrl(originalCover);
                update.setCoverUrl(detailObjectName);
                update.setCoverListUrl(listObjectName);
                update.setCoverDetailUrl(detailObjectName);
                videoMapper.updateById(update);
                // 删除redis视频详情缓存，让下次查询读DB
                redisTemplate.delete(RedisKeys.videoDetail(video.getId()));
                // 清除热门视频卡片缓存
                hotVideoCacheService.invalidateCards();
                videoListCacheService.invalidateAll();
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

    /**
     * 执行FFmpeg外部进程命令，捕获输出日志，处理超时、异常退出
     * @param command ffmpeg完整命令参数集合
     */
    private void runFfmpeg(List<String> command)
            throws IOException, InterruptedException {
        Path logFile = Files.createTempFile(
                "videonest-ffmpeg-" + UUID.randomUUID(),
                ".log"
        );
        // 创建操作系统进程，执行ffmpeg命令，stderr合并stdout，输出重定向到日志文件
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
        boolean completed = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new VideoProcessingException("TIMEOUT", "FFmpeg 处理超时");
        }
        // exitValue不等于0代表ffmpeg执行出错
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

    /**
     * 获取异常简短消息，防止异常message过长存入数据库
     * @param e 异常对象
     * @return 截断后的异常文本
     */
    private String shortMessage(Throwable e) {
        String message = e.getMessage() == null ? "视频处理失败" : e.getMessage();
        return message.length() > 900
                ? message.substring(message.length() - 900)
                : message;
    }

    /**
     * 递归删除本地临时目录及目录下全部文件；先删子文件，再删文件夹
     * @param directory 需要删除的目录Path对象
     */
    private void deleteDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            // 文件排序，子文件优先删除，再删文件夹
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
