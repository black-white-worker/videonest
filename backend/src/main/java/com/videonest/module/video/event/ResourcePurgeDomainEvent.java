package com.videonest.module.video.event;

public record ResourcePurgeDomainEvent(
        ResourcePurgeEvent event,
        long delayMilliseconds
) {
}
