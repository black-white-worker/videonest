package com.videonest.module.follow.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * FollowUserVO 返回数据对象。
 */
@Data
public class FollowUserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private LocalDateTime followedAt;
}
