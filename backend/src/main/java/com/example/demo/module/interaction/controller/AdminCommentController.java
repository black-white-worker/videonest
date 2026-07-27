package com.example.demo.module.interaction.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.service.AdminCommentService;
import com.example.demo.module.interaction.vo.AdminCommentVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@Validated
@RequestMapping("/api/admin/comments")
public class AdminCommentController {
    private final AdminCommentService adminCommentService;

    public AdminCommentController(AdminCommentService adminCommentService) {
        this.adminCommentService = adminCommentService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminCommentVO>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) @Min(1) Long videoId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status
    ) {
        return ApiResponse.success(
                adminCommentService.listComments(page, size, videoId, keyword, status));
    }

    @DeleteMapping("/{commentId}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) Long commentId) {
        adminCommentService.deleteComment(commentId);
        return ApiResponse.success();
    }

    @PutMapping("/{commentId}/restore")
    public ApiResponse<Void> restore(@PathVariable @Min(1) Long commentId) {
        adminCommentService.restoreComment(commentId);
        return ApiResponse.success();
    }
}
