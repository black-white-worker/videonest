package com.videonest.module.video.vo;

import com.videonest.config.RedisConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 【单元测试】验证 HotVideoCardsCacheSerialization 的核心行为与边界条件。
 */
class HotVideoCardsCacheSerializationTest {

    @Test
    void cardsCanRoundTripThroughConfiguredRedisSerializer() {
        VideoListItemVO video = new VideoListItemVO();
        video.setId(7L);
        video.setTitle("热门视频");
        HotVideoCardsCache cache = new HotVideoCardsCache(List.of(video));
        var serializer = RedisConfig.createJsonSerializer();

        Object restored = serializer.deserialize(serializer.serialize(cache));

        assertEquals(cache, restored);
    }
}
