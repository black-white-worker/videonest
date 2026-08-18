package com.videonest.module.video.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 专门存放"热门排行榜"相关的配置参数
 * */
@Data
@Component
// 自动把 application.yml 中以 "hot-rank" 开头的配置项注入到对应字段
@ConfigurationProperties(prefix = "hot-rank")
public class HotRankProperties {

    // 即只统计最近 24 小时内的数据来计算热度
    private int windowHours = 24;

    // 每过 6 小时，热度权重衰减为原来的一半
    private double halfLifeHours = 6D;

    private int maxSize = 50;

    // 超过 180 秒后缓存自动失效，下次请求会重新计算榜单
    private long currentTtlSeconds = 180;

    // "热门卡片列表"缓存的存活时间，
    private long cardsTtlSeconds = 60;

    // 刷新热门榜时分布式锁的持有时间，用于防止多个服务器节点同时刷新榜单造成重复计算
    private long refreshLockSeconds = 40;
}
