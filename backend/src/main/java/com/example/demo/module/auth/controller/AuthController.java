package com.example.demo.module.auth.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.auth.dto.LoginRequest;
import com.example.demo.module.auth.dto.RegisterRequest;
import com.example.demo.module.auth.service.AuthService;
import com.example.demo.module.auth.vo.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * 处理注册、登录接口请求，只负责接收请求、调用service、返回统一响应，不写业务逻辑
 */
/**
 * AuthController 接收登录注册请求，通过@Valid做参数校验、@RequestBody把前端 JSON 转为 DTO 入参，
 * 调用 AuthServiceImpl 业务方法；Service 内部做用户名判重、密码比对、账号状态校验，
 * 条件不满足时直接抛出运行时BusinessException，该异常无需方法声明 throws；
 * 异常向上抛出不被 Controller 捕获，交由全局异常处理器GlobalExceptionHandler统一捕获，包装为ApiResponse统一格式返回前端，
 * 参数校验异常也同样被全局处理器处理，前端通过 message 获取提示信息，后端日志保留完整异常堆栈。
 * */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册接口
     * 请求地址：POST /api/auth/register
     * @ Valid：开启RegisterRequest里面的参数校验，校验失败直接抛出异常
     * @ RequestBody：把前端提交的json转换成RegisterRequest DTO对象
     * 返回 ApiResponse<Void>，不需要返回业务数据，只返回成功状态
     */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success();
    }

    /**
     * 用户登录接口
     * 请求地址：POST /api/auth/login
     * @ Valid校验LoginRequest参数
     * @ RequestBody接收前端json封装到LoginRequest
     * ApiResponse<LoginResponse>：统一包装，内部携带登录VO数据
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}