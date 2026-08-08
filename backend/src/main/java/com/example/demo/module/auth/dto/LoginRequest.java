package com.example.demo.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求DTO
 * 接收前端登录接口提交的json参数：用户名、密码
 * DTO：输入对象，Controller @RequestBody接收前端数据
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}