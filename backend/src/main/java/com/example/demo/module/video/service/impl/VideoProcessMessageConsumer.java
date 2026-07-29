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

    private final ObjectMapper objectMapper;
    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final VideoProcessProperties properties;
    private final VideoReviewProperties reviewProperties;
    private final DelayedMessagePublisher delayedMessagePublisher;
    private final RedisTemplate<String, Object> redisTemplate;

    public VideoProcessMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            MinioService minioService,
            VideoProcessProperties properties,
            VideoReviewProperties reviewProperties,
            DelayedMessagePublisher delayedMessagePublisher,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.properties = properties;
        this.reviewProperties = reviewProperties;
        this.delayedMessagePublisher = delayedMessagePublisher;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.VIDEO_PROCESS_QUEUE)
    public void consume(String message) {
        VideoProcessEvent event = readEvent(message);
        Video video = videoMapper.selectById(event.videoId());

        // 防止 RabbitMQ 重复投递时重复转码。
        if (video == null || !"PROCESSING".equals(video.getStatus())) {
            log.info("跳过重复或过期的视频处理消息，videoId={}", event.videoId());
            return;
        }

        String lockKey = RedisKeys.videoProcessLock(event.videoId());
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                lockKey,
                true,
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
            boolean shouldGenerateCover = !StringUtils.hasText(video.getCoverUrl());
            Path cover = workDir.resolve("cover.jpg");

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
            if (shouldGenerateCover) {
                generateCover(source, cover);
            }

            String basePath = "processed/" + video.getId();
            String video480Name = basePath + "/480p.mp4";
            String video720Name = basePath + "/720p.mp4";
            String video1080Name = basePath + "/1080p.mp4";
            String coverName = "cover/auto/" + video.getId() + ".jpg";

            minioService.uploadFile(video480, video480Name, "video/mp4");
            minioService.uploadFile(video720, video720Name, "video/mp4");
            minioService.uploadFile(video1080, video1080Name, "video/mp4");
            if (shouldGenerateCover) {
                minioService.uploadFile(cover, coverName, "image/jpeg");
            }

            video.setVideo480pUrl(video480Name);
            video.setVideo720pUrl(video720Name);
            video.setVideo1080pUrl(video1080Name);
            video.setVideoUrl(video720Name);
            if (shouldGenerateCover) {
                video.setCoverUrl(coverName);
            }
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
            redisTemplate.delete(lockKey);
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
