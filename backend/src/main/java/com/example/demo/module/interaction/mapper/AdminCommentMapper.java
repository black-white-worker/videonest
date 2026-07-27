package com.example.demo.module.interaction.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.interaction.vo.AdminCommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminCommentMapper {
    IPage<AdminCommentVO> selectAdminCommentPage(
            Page<AdminCommentVO> page,
            @Param("videoId") Long videoId,
            @Param("keyword") String keyword,
            @Param("status") Integer status
    );
}
