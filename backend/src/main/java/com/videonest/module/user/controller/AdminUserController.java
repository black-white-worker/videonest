package com.videonest.module.user.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.user.service.AdminUserService;
import com.videonest.module.user.vo.AdminUserVO;
import com.videonest.module.user.vo.PasswordResetRequestVO;
import com.videonest.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AdminUser 接口控制器。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service = service; }

    /** 查询用户列表，供管理员执行用户管理操作。 */
    @GetMapping
    public ApiResponse<List<AdminUserVO>> listUsers() { return ApiResponse.success(service.listUsers()); }

    /** 查询待处理的密码重置申请。 */
    @GetMapping("/password-reset-requests")
    public ApiResponse<List<PasswordResetRequestVO>> listRequests() { return ApiResponse.success(service.listPendingResetRequests()); }

    /** 为申请指定的用户重置密码，并完成该申请。 */
    @PostMapping("/password-reset-requests/{requestId}/reset")
    public ApiResponse<String> reset(@PathVariable Long requestId, @RequestParam Long userId) {
        return ApiResponse.success(service.resetPassword(requestId, userId, SecurityUtils.getCurrentUser().userId()));
    }
}
