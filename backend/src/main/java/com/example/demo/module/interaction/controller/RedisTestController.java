package com.example.demo.module.interaction.controller;

import com.example.demo.common.api.ApiResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/redis")
public class RedisTestController {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisTestController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/test")
    public ApiResponse<Object> test() {
        String key = "videonest:test";

        redisTemplate.opsForValue().set(
                key,
                "Redis连接成功",
                10,
                TimeUnit.MINUTES
        );

        Object value = redisTemplate.opsForValue().get(key);

        return ApiResponse.success(value);
    }
}