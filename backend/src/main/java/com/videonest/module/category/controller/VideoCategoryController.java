package com.videonest.module.category.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.module.category.entity.VideoCategory;
import com.videonest.module.category.service.VideoCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 视频分类控制器
 */
@RestController
@RequestMapping("/api/categories")
public class VideoCategoryController {

    private final VideoCategoryService videoCategoryService;

    public VideoCategoryController(VideoCategoryService videoCategoryService) {
        this.videoCategoryService = videoCategoryService;
    }

    @GetMapping
    public ApiResponse<List<VideoCategory>> list() {
        return ApiResponse.success(videoCategoryService.listEnabledCategories());
    }
}