package com.videonest.module.video.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * VO：创作者主页信息视图对象
 * 使用场景：创作者后台/创作中心，展示当前登录作者信息和视频统计
 */
@Data
@AllArgsConstructor
public class CreatorProfileVO {

    private Long userId;

    private String username;

    private String nickname;

    private String role;

    private Long totalVideoCount;

    // 待审核视频数量
    private Long pendingVideoCount;

    // 已发布通过审核的视频数量
    private Long publishedVideoCount;

    // 审核被驳回的视频数量
    private Long rejectedVideoCount;
}