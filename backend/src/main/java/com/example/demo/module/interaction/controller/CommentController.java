package com.example.demo.module.interaction.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.dto.CommentCreateRequest;
import com.example.demo.module.interaction.service.CommentService;
import com.example.demo.module.interaction.vo.VideoCommentVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/videos/{videoId}/comments")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

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
