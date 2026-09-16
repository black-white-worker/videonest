package com.videonest.module.video.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.vo.CreatorProfileVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CreatorProfile 接口控制器。
 */
@RestController
@RequestMapping("/api/creator/profile")
public class CreatorProfileController {

    private final VideoService videoService;

    public CreatorProfileController(VideoService videoService) {
        this.videoService = videoService;
    }

    /** 获取当前登录创作者的个人资料和投稿统计信息。 */
    @GetMapping
    public ApiResponse<CreatorProfileVO> getProfile() {
        return ApiResponse.success(
                videoService.getCreatorProfile()
        );
    }
}
