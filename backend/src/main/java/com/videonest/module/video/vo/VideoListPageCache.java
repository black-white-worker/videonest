package com.videonest.module.video.vo;

import java.util.List;

/** Redis 中保存的首页/分类第一页完整读模型。 */
public record VideoListPageCache(
        List<VideoListItemVO> records,
        long total,
        long page,
        long size,
        long pages
) {
}
