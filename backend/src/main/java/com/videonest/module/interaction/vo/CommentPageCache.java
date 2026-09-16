package com.videonest.module.interaction.vo;

import java.util.List;

/**
 * CommentPageCache 后端组件。
 */
public record CommentPageCache(
        List<VideoCommentVO> records,
        long total,
        long page,
        long size,
        long pages
) {
}
