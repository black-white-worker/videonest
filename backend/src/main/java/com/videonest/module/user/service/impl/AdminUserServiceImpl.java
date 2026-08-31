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
import com.videonest.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SysUserMapper userMapper;
    private final PasswordResetRequestMapper requestMapper;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public AdminUserServiceImpl(SysUserMapper userMapper, PasswordResetRequestMapper requestMapper,
                                PasswordEncoder passwordEncoder, ApplicationEventPublisher eventPublisher) {
        this.userMapper = userMapper;
        this.requestMapper = requestMapper;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
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
        if (userMapper.selectById(userId) == null) throw new BusinessException(404, "用户不存在");
        Long count = requestMapper.selectCount(new LambdaQueryWrapper<PasswordResetRequest>()
                .eq(PasswordResetRequest::getUserId, userId).eq(PasswordResetRequest::getStatus, "PENDING"));
        if (count > 0) throw new BusinessException(409, "已有待处理的密码重置申请");
        PasswordResetRequest request = new PasswordResetRequest();
        request.setUserId(userId); request.setStatus("PENDING"); requestMapper.insert(request);
    }

    @Override
    @Transactional
    public String resetPassword(Long requestId, Long userId, Long adminId) {
        PasswordResetRequest request = requestMapper.selectById(requestId);
        if (request == null || !userId.equals(request.getUserId()) || !"PENDING".equals(request.getStatus())) {
            throw new BusinessException(400, "密码重置申请不存在或已处理");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        String temporaryPassword = randomPassword();
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        userMapper.updateById(user);
        if (requestMapper.complete(requestId, adminId) != 1) throw new BusinessException(409, "申请状态已变化，请刷新后重试");
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
