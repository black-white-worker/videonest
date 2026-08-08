package com.example.demo.module.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录接口返回VO对象
 * 专门封装登录成功后返回给前端的数据，不接收前端入参，只做接口出参
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private String role;
}