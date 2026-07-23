package com.example.demo.module.auth.service;

import com.example.demo.module.auth.dto.LoginRequest;
import com.example.demo.module.auth.dto.RegisterRequest;
import com.example.demo.module.auth.vo.LoginResponse;

public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}