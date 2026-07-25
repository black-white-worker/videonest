package com.example.demo.module.video.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.CreatorProfileVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/creator/profile")
public class CreatorProfileController {

    private final VideoService videoService;

    public CreatorProfileController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping
    public ApiResponse<CreatorProfileVO> getProfile() {
        return ApiResponse.success(
                videoService.getCreatorProfile()
        );
    }
}