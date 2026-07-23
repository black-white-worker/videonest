package com.example.demo.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoListItemVO;
import org.springframework.stereotype.Service;

@Service
public class VideoServiceImpl implements VideoService {

    private final VideoMapper videoMapper;

    public VideoServiceImpl(VideoMapper videoMapper) {
        this.videoMapper = videoMapper;
    }

    @Override
    public PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            long page,
            long size
    ) {
        Page<VideoListItemVO> pageRequest = new Page<>(page, size);

        IPage<VideoListItemVO> pageData =
                videoMapper.selectPublishedPage(pageRequest, categoryId);

        return PageResult.of(pageData);
    }
}