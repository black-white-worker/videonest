package com.example.demo.module.interaction.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.interaction.service.InteractionService;
import com.example.demo.module.interaction.vo.InteractionStatusVO;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/videos/{videoId}")
public class InteractionController {

    private final InteractionService interactionService;

    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

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

    @PostMapping("/like")
    public ApiResponse<Void> like(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.like(videoId);
        return ApiResponse.success();
    }

    @DeleteMapping("/like")
    public ApiResponse<Void> unlike(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.unlike(videoId);
        return ApiResponse.success();
    }

    @PostMapping("/favorite")
    public ApiResponse<Void> favorite(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long videoId
    ) {
        interactionService.favorite(videoId);
        return ApiResponse.success();
    }

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