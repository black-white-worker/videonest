package com.videonest.module.notification.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.notification.service.NotificationService;
import com.videonest.module.notification.vo.NotificationVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification 接口控制器。
 */
@RestController
@Validated
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 分页查询当前登录用户可见的通知。 */
    @GetMapping
    public ApiResponse<PageResult<NotificationVO>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size
    ) {
        return ApiResponse.success(
                notificationService.listMyNotifications(page, size)
        );
    }

    /** 获取当前用户的未读通知数，供前端角标展示。 */
    @GetMapping("/unread-count")
    public ApiResponse<Long> getUnreadCount() {
        return ApiResponse.success(notificationService.getMyUnreadCount());
    }

    /** 将当前用户自己的指定通知标记为已读。 */
    @PutMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(
            @PathVariable @Min(1) Long notificationId
    ) {
        notificationService.markRead(notificationId);
        return ApiResponse.success();
    }
}
