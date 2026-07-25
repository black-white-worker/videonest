package com.example.demo.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages
) {
    public static <T> PageResult<T> of(IPage<T> pageData) {
        return new PageResult<>(
                pageData.getRecords(),
                pageData.getTotal(),
                pageData.getCurrent(),
                pageData.getSize(),
                pageData.getPages()
        );
    }
}