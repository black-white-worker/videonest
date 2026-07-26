package com.example.demo.module.follow.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FollowUserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private LocalDateTime followedAt;
}
