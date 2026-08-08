package com.example.demo.module.follow.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定义用户关注的实体类，对应数据库user_follow表，映射用户关注关系数据
 * */
@Data
public class UserFollow {

    private Long id;
    private Long followerId;    // 关注者
    private Long followeeId;    // 被关注者
    private LocalDateTime createdAt;
}
