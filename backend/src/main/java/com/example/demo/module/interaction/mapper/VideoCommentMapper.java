package com.example.demo.module.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.interaction.entity.VideoComment;
import com.example.demo.module.interaction.vo.VideoCommentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VideoCommentMapper extends BaseMapper<VideoComment> {

    IPage<VideoCommentVO> selectCommentPage(
            Page<VideoCommentVO> page,
            @Param("videoId") Long videoId
    );

    IPage<VideoCommentVO> selectReplyPage(
            Page<VideoCommentVO> page,
            @Param("videoId") Long videoId,
            @Param("parentId") Long parentId
    );

    int softDeleteByIdAndUserId(
            @Param("commentId") Long commentId,
            @Param("userId") Long userId
    );

    int softDeleteById(@Param("commentId") Long commentId);

    int softDeleteRepliesByParentId(@Param("parentId") Long parentId);
}