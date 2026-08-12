package com.videonest.module.category.service;

import com.videonest.module.category.entity.VideoCategory;

import java.util.List;

public interface VideoCategoryService {

    List<VideoCategory> listEnabledCategories();
}