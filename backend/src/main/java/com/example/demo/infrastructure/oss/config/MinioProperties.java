package com.example.demo.infrastructure.oss.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO配置属性映射类
 * 自动读取application.yml中前缀为 minio 的配置，封装为Java对象
 */

@Data
//从 application.yml配置文件中，自动读取所有以 minio 开头的配置项，自动绑定到当前类的成员变量
//自动映射赋值到当前类的成员变量，实现配置文件与 Java 实体
//minio.access-key → accessKey（yml的短横线 access-key ↔ Java 驼峰 accessKey，Spring 自动适配
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    // MinIO服务地址
    private String endpoint;

    // 浏览器可访问的MinIO服务地址
    private String publicEndpoint;

    // MinIO存储区域
    private String region;

    // AccessKey 账号
    private String accessKey;

    // SecretKey 密钥
    private String secretKey;

    // 默认存储桶名称
    private String bucketName;

    // 公开读取的对象前缀。只有处理后的媒体资源应暴露给浏览器。
    private String publicReadPrefixes = "processed/,cover/processed/";
}
