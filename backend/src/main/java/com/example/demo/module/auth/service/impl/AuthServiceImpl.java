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

/**
 * 认证服务实现类
 * 实现注册register、登录login核心业务逻辑
 * DTO 负责接前端进来的数据，VO 负责打包数据输出给前端
 */
/**
 * 这段代码实现注册和登录功能，注册接收入参容器 RegisterRequest，
 * 查询数据库 count 防止用户名重复，新建 SysUser 对象加密密码后存入数据库；
 * 登录接收入参容器 LoginRequest，使用 passwordEncoder.matches 比对前端明文密码和数据库密文密码，
 * 校验账号状态后生成 token，将 token、id 等字段封装到出参容器 LoginResponse 返回给前端。
 * */
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
                //LambdaQueryWrapper<SysUser>() 只是用来组装查询条件（where 条件），本身不做分页
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
        // status=1代表账号启用；0代表禁用
        user.setStatus(1);

        // mybatis‑plus插入用户记录，插入之后user对象id会自动回填数据库自增主键
        sysUserMapper.insert(user);
        log.info("用户注册成功，userId={}，username={}", user.getId(), user.getUsername());
    }

    /**
     * 用户登录业务
     * @param request 登录请求DTO（账号密码）
     * @return LoginResponse 返回token、userId、用户名、昵称、角色给前端
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, request.getUsername())
        );

        // request拿的是明文密码， user数据库拿的是密文
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("用户登录失败，用户名或密码错误，username={}", request.getUsername());
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (user.getStatus() == 0) {
            log.warn("禁用用户尝试登录，userId={}，username={}", user.getId(), user.getUsername());
            throw new BusinessException(403, "该账号已被禁用");
        }

        // 使用用户信息组装token内部载体对象，调用工具生成JWT令牌
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
