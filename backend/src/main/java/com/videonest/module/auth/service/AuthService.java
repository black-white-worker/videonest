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

    /**
     * 依据 userId 重新签发一组 access token 会话信息。
     * refresh 接口用它换新 access token，同时也复用了账号状态校验。
     */
    LoginResponse issueSession(Long userId);
}