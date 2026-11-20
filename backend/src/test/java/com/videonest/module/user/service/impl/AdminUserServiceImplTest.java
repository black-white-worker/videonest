package com.videonest.module.user.service.impl;

import com.videonest.common.exception.BusinessException;
import com.videonest.module.user.entity.PasswordResetRequest;
import com.videonest.module.user.entity.SysUser;
import com.videonest.module.user.mapper.PasswordResetRequestMapper;
import com.videonest.module.user.mapper.SysUserMapper;
import com.videonest.security.RefreshTokenService;
import com.videonest.security.TokenVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【单元测试】管理员重置密码必须同时让该用户全部旧会话失效。
 */
class AdminUserServiceImplTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final PasswordResetRequestMapper requestMapper = mock(PasswordResetRequestMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TokenVersionService tokenVersionService = mock(TokenVersionService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

    private AdminUserServiceImpl newService() {
        return new AdminUserServiceImpl(
                userMapper, requestMapper, passwordEncoder, eventPublisher,
                tokenVersionService, refreshTokenService
        );
    }

    @Test
    void resetPasswordBumpsVersionAndRevokesAllRefreshTokens() {
        PasswordResetRequest request = new PasswordResetRequest();
        request.setId(1L);
        request.setUserId(2L);
        request.setStatus("PENDING");

        SysUser user = new SysUser();
        user.setId(2L);
        user.setPassword("old-password");

        when(requestMapper.selectById(1L)).thenReturn(request);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(requestMapper.complete(1L, 3L)).thenReturn(1);

        String temporaryPassword = newService().resetPassword(1L, 2L, 3L);

        // 密码确实被替换
        assertEquals("encoded-password", user.getPassword());
        verify(userMapper).updateById(any(SysUser.class));
        // 关键：版本号 +1 让在途 access token 失效，revokeAll 切断 refresh 续期
        verify(tokenVersionService).bump(2L);
        verify(refreshTokenService).revokeAll(2L);
        // 临时密码仍要交给管理员
        assertEquals(12, temporaryPassword.length());
    }

    @Test
    void stateChangedRequestDoesNotRevokeSessions() {
        PasswordResetRequest request = new PasswordResetRequest();
        request.setId(1L);
        request.setUserId(2L);
        request.setStatus("PENDING");

        SysUser user = new SysUser();
        user.setId(2L);

        when(requestMapper.selectById(1L)).thenReturn(request);
        when(userMapper.selectById(2L)).thenReturn(user);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        // 并发下另一个管理员已经处理过该申请
        when(requestMapper.complete(1L, 3L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> newService().resetPassword(1L, 2L, 3L));

        // 事务会回滚，撤销动作不应发生（否则等于替别人的成功请求做了两遍撤销）
        verify(tokenVersionService, never()).bump(anyLong());
        verify(refreshTokenService, never()).revokeAll(anyLong());
    }
}
