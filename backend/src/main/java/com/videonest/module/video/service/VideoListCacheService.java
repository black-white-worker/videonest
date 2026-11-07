package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.VideoListItemVO;

import java.util.function.Supplier;

/**
 * VideoListCacheService 业务服务接口。
 */
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
     * 当前列表缓存版本号快照（本地缓存 1 秒）。
     * 回源前取一次、回源后连同数据一起交给 {@link #putPage}，
     * 用来判断“回源期间是否发生过写操作失效”。
     */
    String currentVersion();

    /**
     * 写入缓存，并要求版本号与写入时一致。
     *
     * <p>回源是“先读版本 → 查库 → 再写缓存”，中间这 200~300ms 里如果发生写操作，
     * 手里的数据就可能早于最新提交。此时直接丢弃、不写缓存：
     * 下一个请求会重新回源一次，代价远小于把旧数据写进新版本、再存活一整个 TTL。</p>
     */
    void putPage(Long categoryId, long page, long size,
                 PageResult<VideoListItemVO> pageResult, String version);

    /**
     * 写入缓存（以调用瞬间的版本号为准），同时刷新本地时间戳用于软过期判定。
     */
    default void putPage(Long categoryId, long page, long size,
                         PageResult<VideoListItemVO> pageResult) {
        putPage(categoryId, page, size, pageResult, currentVersion());
    }

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
