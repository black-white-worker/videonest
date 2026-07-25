package com.example.demo.module.video.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.video.dto.VideoCreateRequest;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoCreateVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/creator/videos")
public class CreatorVideoController {

    private final VideoService videoService;

    public CreatorVideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @PostMapping
    public ApiResponse<VideoCreateVO> create(
            @Valid @RequestBody VideoCreateRequest request
    ) {
        return ApiResponse.success(videoService.createVideo(request));
    }
}