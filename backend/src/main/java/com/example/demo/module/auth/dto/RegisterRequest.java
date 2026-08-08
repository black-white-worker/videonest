package com.example.demo.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求DTO
 * 接收前端POST提交注册接口的表单/json参数
 * 使用Jakarta Validation做后端参数校验，controller方法参数上加 @Valid 才会触发校验
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(
            regexp = "^[a-zA-Z0-9_]{4,20}$",
            message = "用户名只能包含字母、数字、下划线，长度为 4 到 20 位"
    )
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度应为 6 到 32 位")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(min = 2, max = 32, message = "昵称长度应为 2 到 32 位")
    private String nickname;
}