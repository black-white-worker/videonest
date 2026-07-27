package com.example.demo.module.video.service.impl;

import com.example.demo.infrastructure.mq.RabbitMqConfig;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.module.video.config.VideoProcessProperties;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.event.VideoProcessEvent;
import com.example.demo.module.video.mapper.VideoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VideoProcessMessageConsumer {

    private final ObjectMapper objectMapper;
    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final VideoProcessProperties properties;

    public VideoProcessMessageConsumer(
            ObjectMapper objectMapper,
            VideoMapper videoMapper,
            MinioService minioService,
            VideoProcessProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.properties = properties;
    }

    @RabbitListener(queues = RabbitMqConfig.VIDEO_PROCESS_QUEUE)
    public void consume(String message) throws Exception {
        VideoProcessEvent event = objectMapper.readValue(message, VideoProcessEvent.class);
        Video video = videoMapper.selectById(event.videoId());

        // 防止 RabbitMQ 重复投递时重复转码。
        if (video == null || !"PROCESSING".equals(video.getStatus())) {
            return;
        }

        Path workDir = null;
        try {
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

            transcode(source, video480, 480);
            transcode(source, video720, 720);
            transcode(source, video1080, 1080);
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
            videoMapper.updateById(video);
        } catch (Exception e) {
            log.error("视频处理失败，videoId={}", event.videoId(), e);
            video.setStatus("PROCESS_FAILED");
            video.setProcessError(shortMessage(e));
            videoMapper.updateById(video);
        } finally {
            deleteDirectory(workDir);
        }
    }

    private void transcode(Path source, Path output, int height) throws Exception {
        runFfmpeg(List.of(
                properties.getFfmpegPath(), "-y", "-i", source.toString(),
                // x264 的 yuv420p 输出要求宽高均为偶数。force_divisible_by 避免
                // 非常规分辨率的视频在编码器初始化阶段直接失败。
                "-vf", "scale=-2:" + height
                        + ":force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-map", "0:v:0", "-map", "0:a?",
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-threads", "1",
                "-preset", "medium", "-crf", "23",
                "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                output.toString()
        ));
    }

    private void generateCover(Path source, Path cover) throws Exception {
        runFfmpeg(List.of(
                properties.getFfmpegPath(), "-y", "-ss", "00:00:01",
                "-i", source.toString(), "-frames:v", "1", "-q:v", "2",
                cover.toString()
        ));
    }

    private void runFfmpeg(List<String> command) throws Exception {
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
            throw new IllegalStateException("FFmpeg 处理超时");
        }
        if (process.exitValue() != 0) {
            String output = Files.readString(logFile, StandardCharsets.UTF_8);
            // 数据库只保存摘要，完整命令和日志保留在服务端，便于定位具体素材问题。
            log.error("FFmpeg 执行失败，命令：{}，完整输出：{}", command, output);
            int start = Math.max(0, output.length() - 1600);
            throw new IllegalStateException(
                    "FFmpeg 执行失败，退出码：" + process.exitValue()
                            + "，错误：" + output.substring(start)
            );
        }

        Files.deleteIfExists(logFile);
    }

    private String shortMessage(Exception e) {
        String message = e.getMessage() == null ? "视频处理失败" : e.getMessage();
        return message.length() > 900
                ? message.substring(message.length() - 900)
                : message;
    }

    private void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
