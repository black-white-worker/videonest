package com.videonest.module.video.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.video.dto.VideoReviewRequest;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.service.VideoResourceCleanupService;
import com.videonest.module.video.vo.AdminVideoReviewVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.videonest.module.video.dto.VideoUpdateRequest;
import com.videonest.module.video.vo.DeletedVideoVO;

/**
 * AdminVideo 接口控制器。
 */
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

    /** 分页查询等待管理员审核的视频投稿。 */
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

    /** 提交审核结论，并由服务层驱动视频状态流转。 */
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

    /** 管理员更新指定视频的基础信息。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> updateVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id,
            @Valid @RequestBody VideoUpdateRequest request
    ) {
        videoService.updateAdminVideo(id, request);
        return ApiResponse.success(null);
    }

    /** 将指定视频放入回收站，保留延迟恢复或清理的机会。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id
    ) {
        videoService.deleteAdminVideo(id);
        return ApiResponse.success(null);
    }

    /** 分页查询视频回收站。 */
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

    /** 永久清理回收站视频及其关联的对象存储资源。 */
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
