package com.example.demo.module.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.module.auth.dto.LoginRequest;
import com.example.demo.module.auth.dto.RegisterRequest;
import com.example.demo.module.user.entity.SysUser;
import com.example.demo.module.user.mapper.SysUserMapper;
import com.example.demo.module.auth.service.AuthService;
import com.example.demo.module.auth.vo.LoginResponse;
import com.example.demo.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthServiceImpl(
            SysUserMapper sysUserMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void register(RegisterRequest request) {
        Long count = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
        );

        if (count > 0) {
            log.warn("用户注册失败，用户名已存在，username={}", request.getUsername());
            throw new BusinessException(400, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setRole("USER"); // 注册用户不可自行指定 ADMIN
        user.setStatus(1);

        sysUserMapper.insert(user);
        log.info("用户注册成功，userId={}，username={}", user.getId(), user.getUsername());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
        );

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("用户登录失败，用户名或密码错误，username={}", request.getUsername());
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            log.warn("禁用用户尝试登录，userId={}，username={}", user.getId(), user.getUsername());
            throw new BusinessException(403, "该账号已被禁用");
        }

        String token = jwtTokenProvider.createToken(
                new JwtTokenProvider.SysUserTokenInfo(
                        user.getId(),
                        user.getUsername(),
                        user.getRole()
                )
        );
        log.info("用户登录成功，userId={}，username={}", user.getId(), user.getUsername());

        return new LoginResponse(
                token,
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole()
        );
    }
}
