package com.example.demo.module.category.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.category.entity.VideoCategory;
import com.example.demo.module.category.service.VideoCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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