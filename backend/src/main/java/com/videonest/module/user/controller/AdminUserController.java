package com.videonest.module.user.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.user.service.AdminUserService;
import com.videonest.module.user.vo.AdminUserVO;
import com.videonest.module.user.vo.PasswordResetRequestVO;
import com.videonest.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    private final AdminUserService service;
    public AdminUserController(AdminUserService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<AdminUserVO>> listUsers() { return ApiResponse.success(service.listUsers()); }

    @GetMapping("/password-reset-requests")
    public ApiResponse<List<PasswordResetRequestVO>> listRequests() { return ApiResponse.success(service.listPendingResetRequests()); }

    @PostMapping("/password-reset-requests/{requestId}/reset")
    public ApiResponse<String> reset(@PathVariable Long requestId, @RequestParam Long userId) {
        return ApiResponse.success(service.resetPassword(requestId, userId, SecurityUtils.getCurrentUser().userId()));
    }
}
