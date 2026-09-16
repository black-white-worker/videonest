package com.videonest.module.category.service;

import com.videonest.module.category.entity.VideoCategory;

import java.util.List;

/**
 * VideoCategoryService 业务服务接口。
 */
public interface VideoCategoryService {

    List<VideoCategory> listEnabledCategories();
}
