package com.videonest.module.video.service.impl;

import com.videonest.config.RedisConfig;
import com.videonest.infrastructure.oss.config.MinioProperties;
import com.videonest.infrastructure.oss.service.impl.MinioServiceImpl;
import com.videonest.module.video.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 【一次性微基准】量化视频详情【命中路径】上每一项的单机开销。
 * 只在开发机跑，不进 CI，用来判断 54 QPS 到底是 CPU 工作还是排队/IO 造成的。
 * 运行：mvn -o -Dtest=DetailHitPathMicroBenchmarkTest test
 */
class DetailHitPathMicroBenchmarkTest {

    private static VideoDetailVO transcodedDetail() {
        VideoDetailVO vo = new VideoDetailVO();
        vo.setId(4L);
        vo.setTitle("Spring Boot 从零搭建视频平台，包含缓存击穿与穿透防护的完整实战记录");
        vo.setDescription("后端开发记录，涵盖 Redis 二级缓存、MinIO 预签名、Outbox 可靠消息。".repeat(6));
        vo.setCoverUrl("cover/processed/4/detail-1080.jpg");
        vo.setVideoUrl("processed/4/720p.mp4");
        vo.setVideo480pUrl("processed/4/480p.mp4");
        vo.setVideo720pUrl("processed/4/720p.mp4");
        vo.setVideo1080pUrl("processed/4/1080p.mp4");
        vo.setVideo480pSizeBytes(18_456_321L);
        vo.setVideo720pSizeBytes(41_223_001L);
        vo.setVideo1080pSizeBytes(88_774_512L);
        vo.setDuration(600);
        vo.setViewCount(128L);
        vo.setLikeCount(18L);
        vo.setFavoriteCount(5L);
        vo.setPublishTime(LocalDateTime.now());
        vo.setAuthorId(1L);
        vo.setAuthorUsername("admin");
        vo.setAuthorNickname("管理员");
        vo.setCategoryId(4L);
        vo.setCategoryName("后端开发");
        return vo;
    }

    private static VideoDetailVO externalLinkDetail() {
        VideoDetailVO vo = transcodedDetail();
        vo.setCoverUrl("https://images.unsplash.com/photo-1516321318423-f06f85e504b3?w=800");
        vo.setVideoUrl("https://www.w3schools.com/html/mov_bbb.mp4");
        vo.setVideo480pUrl(null);
        vo.setVideo720pUrl(null);
        vo.setVideo1080pUrl(null);
        vo.setVideo480pSizeBytes(null);
        vo.setVideo720pSizeBytes(null);
        vo.setVideo1080pSizeBytes(null);
        return vo;
    }

    private static MinioServiceImpl minio(String cdnEndpoint) {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setPublicEndpoint("http://101.201.208.223:9010");
        properties.setCdnEndpoint(cdnEndpoint);
        properties.setRegion("us-east-1");
        properties.setAccessKey("videonest");
        properties.setSecretKey("videonest-secret");
        properties.setBucketName("videonest");
        properties.setPublicReadPrefixes("processed/,cover/processed/");
        return new MinioServiceImpl(null, properties);
    }

    /** 详情里 5 个地址各生成一次访问 URL。 */
    private static String fiveUrls(MinioServiceImpl minio, VideoDetailVO vo) {
        return minio.getAccessUrl(vo.getCoverUrl())
                + minio.getAccessUrl(vo.getVideoUrl())
                + minio.getAccessUrl(vo.getVideo480pUrl())
                + minio.getAccessUrl(vo.getVideo720pUrl())
                + minio.getAccessUrl(vo.getVideo1080pUrl());
    }

    private static void report(String label, int iterations, long nanoTotal) {
        double nsPerOp = (double) nanoTotal / iterations;
        System.out.printf("%-42s %,10d 次  %8.0f ns/次  %6.3f µs/次%n",
                label, iterations, nsPerOp, nsPerOp / 1000);
    }

    private static long timeOps(int iterations, Runnable body) {
        for (int i = 0; i < Math.min(iterations, 20_000); i++) {
            body.run();
        }
        long started = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            body.run();
        }
        return System.nanoTime() - started;
    }

    @Test
    void measureDetailHitPathComponents() {
        VideoDetailVO transcoded = transcodedDetail();
        VideoDetailVO external = externalLinkDetail();
        MinioServiceImpl originOnly = minio("");
        MinioServiceImpl cdn = minio("https://media.example.com");
        GenericJacksonJsonRedisSerializer serializer = RedisConfig.createJsonSerializer();
        byte[] payload = serializer.serialize(transcoded);
        System.out.printf("%n缓存值 JSON 字节数 = %d%n%n", payload.length);

        int deserializeIterations = 200_000;
        report("Redis 值反序列化 (Jackson)", deserializeIterations,
                timeOps(deserializeIterations, () -> {
                    Object out = serializer.deserialize(
                            new String(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
                    if (!(out instanceof VideoDetailVO)) {
                        throw new IllegalStateException("反序列化类型不符");
                    }
                }));

        report("Redis 值序列化 (Jackson)", 200_000,
                timeOps(200_000, () -> serializer.serialize(transcoded)));

        report("BeanUtils.copyProperties(详情VO)", 500_000,
                timeOps(500_000, () -> {
                    VideoDetailVO copy = new VideoDetailVO();
                    BeanUtils.copyProperties(transcoded, copy);
                }));

        report("5 次 getAccessUrl（processed 公开读）", 200_000,
                timeOps(200_000, () -> fiveUrls(cdn, transcoded)));

        report("5 次 getAccessUrl（源站无 CDN）", 200_000,
                timeOps(200_000, () -> fiveUrls(originOnly, transcoded)));

        report("5 次 getAccessUrl（外链直放）", 500_000,
                timeOps(500_000, () -> fiveUrls(cdn, external)));

        report("UUID.randomUUID（日志过滤器每请求）", 500_000,
                timeOps(500_000, () -> UUID.randomUUID().toString().replace("-", "")));

        System.out.println("换算公式：单请求 CPU 总纳秒 ≈ 反序列化 + 拷贝 + 5 次 URL 生成；"
                + "2 核机器若 CPU 是唯一瓶颈，上限 ≈ 2_000_000_000 / 单请求 CPU 总纳秒 rps");
    }
}
