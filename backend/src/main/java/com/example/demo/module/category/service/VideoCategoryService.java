package com.example.demo.module.category.service;

import com.example.demo.module.category.entity.VideoCategory;

import java.util.List;

public interface VideoCategoryService {

    List<VideoCategory> listEnabledCategories();
}