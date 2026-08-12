package com.videonest.module.auth.service;

import com.videonest.module.auth.dto.LoginRequest;
import com.videonest.module.auth.dto.RegisterRequest;
import com.videonest.module.auth.vo.LoginResponse;

/**
 * 认证业务接口
 * 定义登录、注册的业务方法规范，只声明方法，不写具体业务实现
 */
public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}