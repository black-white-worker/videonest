package com.example.demo.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**分页结果对象
 * */

public record PageResult<T>(
        List<T> records,
        long total,
        long page,
        long size,
        long pages
) {
    /*可以直接传IPage类的对象pageDate而不用像new一样传准确的参数*/
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