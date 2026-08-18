package com.videonest.security;
/**
 * 用户登录实体类
 * */
public record LoginUser(
        Long userId,
        String username,
        String role
) {
}