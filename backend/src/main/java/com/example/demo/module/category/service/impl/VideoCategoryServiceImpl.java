package com.example.demo.module.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.demo.module.category.entity.VideoCategory;
import com.example.demo.module.category.mapper.VideoCategoryMapper;
import com.example.demo.module.category.service.VideoCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 视频分类业务层实现类
 * 实现 VideoCategoryService 接口，编写具体业务逻辑
 */
@Service
public class VideoCategoryServiceImpl implements VideoCategoryService {

    private final VideoCategoryMapper videoCategoryMapper;

    public VideoCategoryServiceImpl(VideoCategoryMapper videoCategoryMapper) {
        this.videoCategoryMapper = videoCategoryMapper;
    }

    /**
     * 查询启用状态的分类列表
     * @return 启用的分类集合
     */
    @Override
    public List<VideoCategory> listEnabledCategories() {
        return videoCategoryMapper.selectList(
                new LambdaQueryWrapper<VideoCategory>()
                        .eq(VideoCategory::getStatus, 1)
                        // orderByAsc：升序排序，优先按sortNum排序，数字越小越靠前
                        .orderByAsc(VideoCategory::getSortNum)
                        // sortNum相同情况下，再按id升序，保证排序稳定
                        .orderByAsc(VideoCategory::getId)
        );
    }
}