package com.example.demo.module.video.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoDetailVO;
import com.example.demo.module.video.vo.VideoListItemVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    @GetMapping
    public ApiResponse<PageResult<VideoListItemVO>> list(
            @RequestParam(required = false) Long categoryId,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "12")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                videoService.listPublishedVideos(categoryId, page, size)
        );
    }


    @GetMapping("/{id}")
    public ApiResponse<VideoDetailVO> detail(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long id
    ) {
        return ApiResponse.success(
                videoService.getPublishedVideoDetail(id)
        );
    }

    @GetMapping("/hot")
    public ApiResponse<List<VideoListItemVO>> hot(
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "热榜数量必须大于 0")
            @Max(value = 50, message = "热榜数量不能超过 50")
            int limit
    ) {
        return ApiResponse.success(videoService.listHotVideos(limit));
    }



}
