package com.videonest.module.user.service;

import com.videonest.module.user.vo.AdminUserVO;
import com.videonest.module.user.vo.PasswordResetRequestVO;

import java.util.List;

public interface AdminUserService {
    List<AdminUserVO> listUsers();
    List<PasswordResetRequestVO> listPendingResetRequests();
    String resetPassword(Long requestId, Long userId, Long adminId);
    void requestPasswordReset(Long userId);
}
