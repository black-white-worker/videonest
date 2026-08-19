package com.videonest.module.interaction.vo;

import java.util.List;

public record CommentPageCache(
        List<VideoCommentVO> records,
        long total,
        long page,
        long size,
        long pages
) {
}
