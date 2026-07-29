package com.example.demo.module.video.event;

public record ResourcePurgeDomainEvent(
        ResourcePurgeEvent event,
        long delayMilliseconds
) {
}
