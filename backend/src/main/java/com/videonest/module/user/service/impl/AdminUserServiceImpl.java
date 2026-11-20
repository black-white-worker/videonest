package com.videonest.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.user.entity.PasswordResetRequest;
import com.videonest.module.user.entity.SysUser;
import com.videonest.module.user.mapper.PasswordResetRequestMapper;
import com.videonest.module.user.mapper.SysUserMapper;
import com.videonest.module.user.service.AdminUserService;
import com.videonest.module.user.vo.AdminUserVO;
import com.videonest.module.user.vo.PasswordResetRequestVO;
import com.videonest.security.RefreshTokenService;
import com.videonest.security.SecurityUtils;
import com.videonest.security.TokenVersionService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * AdminUserService 的默认业务实现。
 */
@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SysUserMapper userMapper;
    private final PasswordResetRequestMapper requestMapper;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenVersionService tokenVersionService;
    private final RefreshTokenService refreshTokenService;

    public AdminUserServiceImpl(SysUserMapper userMapper, PasswordResetRequestMapper requestMapper,
                                PasswordEncoder passwordEncoder, ApplicationEventPublisher eventPublisher,
                                TokenVersionService tokenVersionService, RefreshTokenService refreshTokenService) {
        this.userMapper = userMapper;
        this.requestMapper = requestMapper;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.tokenVersionService = tokenVersionService;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    public List<AdminUserVO> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getId))
                .stream().map(u -> new AdminUserVO(u.getId(), u.getUsername(), u.getNickname(), u.getStatus(), u.getRole())).toList();
    }

    @Override
    public List<PasswordResetRequestVO> listPendingResetRequests() {
        return requestMapper.selectPending();
    }

    @Override
    @Transactional
    public void requestPasswordReset(Long userId) {
        // 目标用户必须存在
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }
        // 同一用户不能存在多个待处理的密码重置申请
        Long count = requestMapper.selectCount(new LambdaQueryWrapper<PasswordResetRequest>()
                .eq(PasswordResetRequest::getUserId, userId).eq(PasswordResetRequest::getStatus, "PENDING"));
        if (count > 0) {
            throw new BusinessException(409, "已有待处理的密码重置申请");
        }
        // 创建待处理申请并入库
        PasswordResetRequest request = new PasswordResetRequest();
        request.setUserId(userId);
        request.setStatus("PENDING");
        requestMapper.insert(request);
    }

    @Override
    @Transactional
    public String resetPassword(Long requestId, Long userId, Long adminId) {
        PasswordResetRequest request = requestMapper.selectById(requestId);
        if (request == null || !userId.equals(request.getUserId()) || !"PENDING".equals(request.getStatus())) {
            throw new BusinessException(400, "密码重置申请不存在或已处理");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        String temporaryPassword = randomPassword();
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        userMapper.updateById(user);
        // 用 UPDATE ... WHERE status='PENDING' 保证并发下只有一条申请能被处理
        if (requestMapper.complete(requestId, adminId) != 1) {
            throw new BusinessException(409, "申请状态已变化，请刷新后重试");
        }

        // 改密必须让旧会话立刻失效：管理员重置密码通常发生在账号疑似泄露的场景，
        // 只改 password 不动凭证，等于把攻击者已有的 refresh token 又续了 7 天。
        // 版本号 +1 作用在在途 access token（受本地缓存影响，最多 30 秒收敛），
        // revokeAll 删除该用户所有设备的 refresh token，切断续期。
        // Redis 故障时这里会抛异常并回滚整笔事务——宁可“密码没改成功”，
        // 也不接受“密码改了但旧会话还活着”的中间态。
        tokenVersionService.bump(userId);
        refreshTokenService.revokeAll(userId);

        eventPublisher.publishEvent(new NotificationDomainEvent(new NotificationEvent(
                UUID.randomUUID().toString(), userId, adminId, "PASSWORD_RESET", null, null,
                "管理员已初始化你的登录密码，请通过安全渠道获取临时密码，并登录后立即修改。")));
        return temporaryPassword;
    }

    private String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) value.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return value.toString();
    }
}
