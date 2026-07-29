package com.example.demo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主动注入并调用 ObjectMapper,负责 JSON ↔ Java 实体 的序列化 / 反序列化
 * Java 实体 → JSON 字符串（序列化）
 * objectMapper.writeValueAsString(实体对象)
 * JSON 字符串 → Java 实体（反序列化）
 * objectMapper.readValue(json字符串, 目标类.class)
 * */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        /*自动从项目依赖中搜索所有 Jackson 扩展模块，自动加载、注册到当前 ObjectMapper。*/
        return new ObjectMapper()
                .findAndRegisterModules();
    }
}