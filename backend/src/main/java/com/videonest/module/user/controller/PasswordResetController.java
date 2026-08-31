package com.videonest.module.user.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.user.service.AdminUserService;
import com.videonest.security.SecurityUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/password-reset-requests")
public class PasswordResetController {
    private final AdminUserService service;
    public PasswordResetController(AdminUserService service) { this.service = service; }
    @PostMapping
    public ApiResponse<Void> request() {
        service.requestPasswordReset(SecurityUtils.getCurrentUser().userId());
        return ApiResponse.success(null);
    }
}
