package com.videonest.module.interaction.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 视频交互状态VO
 * 用于查询当前登录用户对某条视频的点赞、收藏状态，以及总点赞数、总收藏数
 */
@Data
@AllArgsConstructor
public class InteractionStatusVO {

    private boolean liked;

    private boolean favorited;

    // 点赞量
    private Long likeCount;

    // 收藏量
    private Long favoriteCount;
}