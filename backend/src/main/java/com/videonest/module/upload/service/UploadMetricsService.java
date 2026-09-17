package com.videonest.module.upload.service;

import com.videonest.module.upload.dto.UploadMetricRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 上传埋点服务：收集前端上报的上传事件、耗时与字节数，
 * 统一写入 Micrometer 指标，供监控面板统计上传成功率、耗时分布与流量。
 */
@Service
public class UploadMetricsService {

    private final MeterRegistry registry;

    public UploadMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次上传事件埋点。
     *
     * @param request 前端上报的埋点数据（模式、事件、大小、耗时、失败原因）
     */
    public void record(UploadMetricRequest request) {
        Tags tags = Tags.of("mode", request.getMode(), "event", request.getEvent(),
                "reason", request.getReason() == null || request.getReason().isBlank() ? "none" : request.getReason());
        // 事件计数：按 mode/event/reason 打点，如 multipart/put_failed/timeout
        registry.counter("videonest.upload.events", tags).increment();
        // 耗时统计：仅记录带有效耗时的请求
        if (request.getDurationMs() > 0) {
            registry.timer("videonest.upload.duration", tags).record(Duration.ofMillis(request.getDurationMs()));
        }
        // 上传字节数分布：按 mode/event 统计
        registry.summary("videonest.upload.bytes", Tags.of("mode", request.getMode(), "event", request.getEvent()))
                .record(request.getSize());
    }
}
