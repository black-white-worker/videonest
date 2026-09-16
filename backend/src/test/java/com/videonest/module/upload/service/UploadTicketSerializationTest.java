package com.videonest.module.upload.service;

import org.junit.jupiter.api.Test;
import com.videonest.config.RedisConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 【单元测试】验证 UploadTicketSerialization 的核心行为与边界条件。
 */
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
