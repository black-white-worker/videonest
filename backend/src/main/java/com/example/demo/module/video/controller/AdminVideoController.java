package com.example.demo.module.video.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.dto.VideoReviewRequest;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.service.VideoResourceCleanupService;
import com.example.demo.module.video.vo.AdminVideoReviewVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.example.demo.module.video.dto.VideoUpdateRequest;
import com.example.demo.module.video.vo.DeletedVideoVO;

@RestController
@Validated
@RequestMapping("/api/admin/videos")
public class AdminVideoController {

    private final VideoService videoService;
    private final VideoResourceCleanupService cleanupService;

    public AdminVideoController(
            VideoService videoService,
            VideoResourceCleanupService cleanupService
    ) {
        this.videoService = videoService;
        this.cleanupService = cleanupService;
    }

    @GetMapping("/pending")
    public ApiResponse<PageResult<AdminVideoReviewVO>> pendingList(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                videoService.listPendingReviewVideos(page, size)
        );
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Void> review(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long id,

            @Valid
            @RequestBody VideoReviewRequest request
    ) {
        videoService.reviewVideo(
                id,
                request.getAction(),
                request.getRejectReason()
        );

        return ApiResponse.success(null);
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id,
            @Valid @RequestBody VideoUpdateRequest request
    ) {
        videoService.updateAdminVideo(id, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id
    ) {
        videoService.deleteAdminVideo(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/deleted")
    public ApiResponse<PageResult<DeletedVideoVO>> deletedList(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                cleanupService.listDeletedVideos(page, size)
        );
    }

    @DeleteMapping("/{id}/purge")
    public ApiResponse<Void> purgeVideo(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long id
    ) {
        cleanupService.purgeVideo(id);
        return ApiResponse.success();
    }

}
