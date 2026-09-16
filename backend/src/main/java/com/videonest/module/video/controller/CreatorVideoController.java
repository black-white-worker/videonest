package com.videonest.module.video.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.video.dto.VideoCreateRequest;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.vo.VideoCreateVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.videonest.module.video.dto.VideoUpdateRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * CreatorVideo 接口控制器。
 */
@RestController
@Validated
@RequestMapping("/api/creator/videos")
public class CreatorVideoController {

    private final VideoService videoService;

    public CreatorVideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    /** 创建投稿记录；文件对象必须已通过上传会话的安全确认。 */
    // 创建投稿
    @PostMapping
    public ApiResponse<VideoCreateVO> create(
            @Valid @RequestBody VideoCreateRequest request
    ) {
        return ApiResponse.success(videoService.createVideo(request));
    }

    /** 分页查询当前登录创作者的投稿及处理状态。 */
    // 获取我的投稿列表
    @GetMapping
    public ApiResponse<PageResult<CreatorVideoListVO>> listMyVideos(
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                videoService.listCreatorVideos(page, size)
        );
    }

    /** 分页查询当前用户点赞过的视频。 */
    @GetMapping("/liked")
    public ApiResponse<PageResult<com.videonest.module.video.vo.VideoListItemVO>> listMyLikedVideos(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) long size
    ) {
        return ApiResponse.success(videoService.listMyLikedVideos(page, size));
    }

    /** 分页查询当前用户收藏的视频。 */
    @GetMapping("/favorites")
    public ApiResponse<PageResult<com.videonest.module.video.vo.VideoListItemVO>> listMyFavoritedVideos(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) long size
    ) {
        return ApiResponse.success(videoService.listMyFavoritedVideos(page, size));
    }

    /** 更新当前用户自己的投稿，服务层会校验作者归属和状态。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> updateMyVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id,
            @Valid @RequestBody VideoUpdateRequest request
    ) {
        videoService.updateCreatorVideo(id, request);
        return ApiResponse.success(null);
    }

    /** 删除当前用户自己的投稿；资源清理由后续流程延迟执行。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMyVideo(
            @PathVariable @Min(value = 1, message = "视频 ID 不合法") Long id
    ) {
        videoService.deleteCreatorVideo(id);
        return ApiResponse.success(null);
    }


}
