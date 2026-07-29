package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisTemplate 自定义序列化配置
 * 解决Spring默认RedisTemplate使用Jdk序列化产生乱码、二进制key的问题
 */

/**在 RedisConfig 中创建JSON序列化器jsonSerializer，和字符序列化器stringSerializer
作为局部对象装配进 RedisTemplate，仅将配置完成的 RedisTemplate
注册为 Bean 供业务注入使用*/

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        //创建RedisTemplate实例，用来执行Redis各种命令
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        //连接工厂，提供和Redis服务建立连接的能力
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Spring Data Redis 4.x 推荐的 JSON 序列化器，key统一存字符串
        //将Java对象自动转为JSON存入Redis，取出自动转回对象
        // GenericJacksonJsonRedisSerializer 使用Jackson完成序列化
        GenericJacksonJsonRedisSerializer jsonSerializer =
                GenericJacksonJsonRedisSerializer.builder().build();

        // 普通 key / hash key 使用字符串序列化，这样可读
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 普通 value / hash value 使用 JSON，实现跨语言兼容
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // 加载所有配置，初始化序列化相关参数
        template.afterPropertiesSet();
        return template;
    }
}