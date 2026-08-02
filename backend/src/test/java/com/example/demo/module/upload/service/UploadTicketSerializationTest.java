package com.example.demo.module.upload.service;

import org.junit.jupiter.api.Test;
import com.example.demo.config.RedisConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadTicketSerializationTest {

    @Test
    void ticketCanRoundTripThroughConfiguredRedisSerializer() {
        var serializer = RedisConfig.createJsonSerializer();
        var ticket = new UploadTicket(
                "upload-1", 7L, "video", "staging/7/file.mp4",
                "video/7/file.mp4", 1024L
        );

        Object restored = serializer.deserialize(serializer.serialize(ticket));

        assertEquals(ticket, restored);
    }
}
