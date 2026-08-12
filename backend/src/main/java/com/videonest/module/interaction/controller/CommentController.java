package com.videonest.module.interaction.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.interaction.dto.CommentCreateRequest;
import com.videonest.module.interaction.service.CommentService;
import com.videonest.module.interaction.vo.VideoCommentVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 视频前台评论控制器
 * 基础路径：/api/videos/{videoId}/comments
 */
/**
 * 路径 ID 用@PathVariable、分页筛选条件即？后面的查询用@RequestParam、提交表单 JSON 用@RequestBody，
 * 三类参数校验后调用 Service 处理，最终结果统一封装为ApiResponse返回前端。
 * */
/*
* @PathVariable：拿路径里大括号 {}的 ID
* @RequestParam：拿问号？后面的查询参数
* @RequestBody：拿POST 请求 Body 里的 JSON，
* create新增评论接口用@RequestBody拿dto CommentCreateRequest request
* */
@RestController
@Validated
@RequestMapping("/api/videos/{videoId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /**
     * 查询某个视频下的一级评论分页列表
     * 请求方式：GET  查询操作
     * 地址：GET /api/videos/{videoId}/comments
     */
    @GetMapping
    public ApiResponse<PageResult<VideoCommentVO>> list(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                commentService.listComments(videoId, page, size)
        );
    }

    /**
     * 用户发布新评论接口
     * 请求方式：POST 新增资源
     * 地址：POST /api/videos/{videoId}/comments
     * 重点：本接口用到了 @Valid + @RequestBody + DTO 经典入参模式
     */
    @PostMapping
    public ApiResponse<Void> create(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId,

            @Valid
            @RequestBody
            CommentCreateRequest request
    ) {
        commentService.createComment(videoId, request);
        return ApiResponse.success();
    }

    /**
     * 查询某条评论下的回复（二级子评论）分页
     * GET /api/videos/{videoId}/comments/{commentId}/replies
     */
    @GetMapping("/{commentId}/replies")
    public ApiResponse<PageResult<VideoCommentVO>> listReplies(
            @PathVariable @Min(1) Long videoId,
            @PathVariable @Min(1) Long commentId,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) long size
    ) {
        return ApiResponse.success(
                commentService.listReplies(videoId, commentId, page, size)
        );
    }

    /**
     * 删除自己发布的评论（前台用户删除）
     * DELETE /api/videos/{videoId}/comments/{commentId}
     */
    @DeleteMapping("/{commentId}")
    public ApiResponse<Void> delete(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId,

            @PathVariable
            @Min(value = 1, message = "评论 ID 必须大于 0")
            Long commentId
    ) {
        commentService.deleteComment(commentId);
        return ApiResponse.success();
    }
}
