package com.example.demo.module.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.module.category.entity.VideoCategory;
import com.example.demo.module.category.mapper.VideoCategoryMapper;
import com.example.demo.module.category.service.VideoCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VideoCategoryServiceImpl implements VideoCategoryService {

    private final VideoCategoryMapper videoCategoryMapper;

    public VideoCategoryServiceImpl(VideoCategoryMapper videoCategoryMapper) {
        this.videoCategoryMapper = videoCategoryMapper;
    }

    @Override
    public List<VideoCategory> listEnabledCategories() {
        return videoCategoryMapper.selectList(
                new LambdaQueryWrapper<VideoCategory>()
                        .eq(VideoCategory::getStatus, 1)
                        .orderByAsc(VideoCategory::getSortNum)
                        .orderByAsc(VideoCategory::getId)
        );
    }
}