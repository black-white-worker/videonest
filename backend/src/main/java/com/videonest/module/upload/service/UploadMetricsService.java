package com.videonest.module.upload.service;

import com.videonest.module.upload.dto.UploadMetricRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Service;

@Service
public class UploadMetricsService {
    private final MeterRegistry registry;
    public UploadMetricsService(MeterRegistry registry) { this.registry = registry; }
    public void record(UploadMetricRequest request) {
        Tags tags = Tags.of("mode", request.getMode(), "event", request.getEvent(),
                "reason", request.getReason() == null || request.getReason().isBlank() ? "none" : request.getReason());
        registry.counter("videonest.upload.events", tags).increment();
        if (request.getDurationMs() > 0) {
            registry.timer("videonest.upload.duration", tags).record(java.time.Duration.ofMillis(request.getDurationMs()));
        }
        registry.summary("videonest.upload.bytes", Tags.of("mode", request.getMode(), "event", request.getEvent()))
                .record(request.getSize());
    }
}
