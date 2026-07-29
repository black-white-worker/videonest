package com.example.demo.infrastructure.oss.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储客户端配置类
 * 作用：项目启动时创建MinioClient客户端，交给Spring管理，业务直接注入使用
 */

@Configuration
// 启用属性绑定：自动读取application.yml中的minio配置，封装为MinioProperties对象
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /**
     * 创建MinIO客户端Bean
     * Spring自动调用此方法，生成MinioClient并存入IOC容器
     * @param properties Spring自动注入已经加载yml参数的属性对象
     * @return MinioClient MinIO操作客户端
     */
    /**MinIO 想要建立连接、正常通信，必须携带 3 组信息：服务地址、账号、密钥
     * Spring 先完成 yml → MinioProperties 对象映射赋值，
     * 再把已经填充好所有配置的 MinioProperties 对象自动注入到 minioClient() 方法入参
     * */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                //设置密钥凭证
                .credentials(
                        properties.getAccessKey(),
                        properties.getSecretKey()
                )
                .build();
    }
}