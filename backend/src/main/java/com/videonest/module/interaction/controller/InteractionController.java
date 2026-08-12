package com.videonest.module.interaction.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.interaction.service.InteractionService;
import com.videonest.module.interaction.vo.InteractionStatusVO;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 视频互动控制器
 * 顶层父路由：/api/videos/{videoId}
 */
@RestController
@Validated
@RequestMapping("/api/videos/{videoId}")
public class InteractionController {

    private final InteractionService interactionService;

    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * GET 查询视频整体互动状态
     * 作用：前端进入视频详情页一次性获取点赞数、收藏数、当前用户是否点赞/收藏
     */
    @GetMapping("/interaction")
    public ApiResponse<InteractionStatusVO> getStatus(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        return ApiResponse.success(
                interactionService.getStatus(videoId)
        );
    }

    /**
     * POST 给视频点赞
     * 地址：POST /api/videos/{videoId}/like
     * REST规范：POST代表新增一条点赞记录
     */
    @PostMapping("/like")
    public ApiResponse<Void> like(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.like(videoId);
        return ApiResponse.success();
    }

    /**
     * DELETE 取消点赞
     * 地址：DELETE /api/videos/{videoId}/like
     */
    @DeleteMapping("/like")
    public ApiResponse<Void> unlike(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.unlike(videoId);
        return ApiResponse.success();
    }

    /**
     * POST 收藏视频
     * 地址：POST /api/videos/{videoId}/favorite
     */
    @PostMapping("/favorite")
    public ApiResponse<Void> favorite(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.favorite(videoId);
        return ApiResponse.success();
    }

    /**
     * DELETE 取消收藏
     * 地址：DELETE /api/videos/{videoId}/favorite
     */
    @DeleteMapping("/favorite")
    public ApiResponse<Void> unfavorite(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.unfavorite(videoId);
        return ApiResponse.success();
    }
}