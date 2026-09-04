package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoListItemVO;

import java.util.function.Supplier;

public interface VideoListCacheService {

    /**
     * 缓存读取结果。
     *
     * @param data  原始列表数据（未签名，coverUrl 为 MinIO 对象名或原始地址，签名由上层返回前统一处理）
     * @param stale 是否已过软过期：true 表示数据仍可用，但建议触发一次后台异步刷新（SWR）
     */
    record CacheLookup(PageResult<VideoListItemVO> data, boolean stale) {
    }

    /**
     * 读取缓存（本地 Caffeine → Redis），两层都未命中返回 null。
     */
    CacheLookup getPage(Long categoryId, long page, long size);

    /**
     * 写入缓存（Redis + 本地 Caffeine），同时刷新本地时间戳用于软过期判定。
     */
    void putPage(Long categoryId, long page, long size, PageResult<VideoListItemVO> pageResult);

    /**
     * SWR（Stale-While-Revalidate）：
     * 后台异步重建指定页缓存，loader 负责回源数据库构建原始数据（未签名）。
     * 同一 key 同时只允许一个在途刷新任务，避免并发重复回源打爆数据库。
     */
    void refreshAsync(Long categoryId, long page, long size,
                      Supplier<PageResult<VideoListItemVO>> loader);

    /**
     * 主动失效全部列表缓存（写操作后调用），本地缓存与 Redis 一并清空。
     */
    void invalidateAll();
}
