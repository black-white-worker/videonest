package com.videonest.infrastructure.outbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 消息发件箱模式（Outbox）的Spring配置类
 * 核心作用：将OutboxProperties配置属性类交由Spring容器管理，自动读取yml/yml中的配置参数并注入
 */
/*
 * @ EnableConfigurationProperties 注解作用：
 * 1. 开启指定配置属性类的自动绑定功能
 * 2. 告诉SpringBoot去读取application.yml中前缀匹配OutboxProperties类@ConfigurationProperties注解的配置项
 * 3. 自动把配置文件里的参数赋值到OutboxProperties实体类的成员变量中，并将OutboxProperties注册为Spring Bean，可直接@Resource注入使用
 * 参数OutboxProperties.class：指定要启用自动配置绑定的属性配置类
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
