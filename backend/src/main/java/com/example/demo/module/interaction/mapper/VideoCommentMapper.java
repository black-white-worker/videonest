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

    /**
     * 分页查询视频一级主评论列表
     * @param page MP分页对象，自动处理limit分页
     * @param videoId 视频主键ID，根据视频筛选所属评论
     * @return IPage分页对象，包含总记录数、当前页评论VO集合
     */
    IPage<VideoCommentVO> selectCommentPage(
            Page<VideoCommentVO> page,
            @Param("videoId") Long videoId
    );

    /**
     * 分页查询某条主评论下的二级回复评论
     * @param page 分页对象
     * @param videoId 所属视频ID
     * @param parentId 父评论ID，关联主评论查询其子回复
     * @return 分页包装的回复评论VO数据
     */
    IPage<VideoCommentVO> selectReplyPage(
            Page<VideoCommentVO> page,
            @Param("videoId") Long videoId,
            @Param("parentId") Long parentId
    );

    /**
     * 根据评论ID和操作人用户ID执行评论软删除
     * @param commentId 要删除的评论主键
     * @param userId 当前操作删除的登录用户ID，用于数据权限校验
     * @return int 数据库受影响行数，1代表删除成功，0代表失败
     */
    int softDeleteByIdAndUserId(
            @Param("commentId") Long commentId,
            @Param("userId") Long userId
    );

    /**
     * 管理员直接根据评论ID软删除评论（无用户权限校验）
     * @param commentId 评论主键id
     * @return 受影响行数
     */
    int softDeleteById(@Param("commentId") Long commentId);

    /**
     * 根据根评论ID，批量软删除该讨论串下所有回复
     * @param rootId 一级根评论ID
     * @return 批量删除的受影响行数
     */
    int softDeleteRepliesByRootId(@Param("rootId") Long rootId);

    int restoreRepliesByRootId(@Param("rootId") Long rootId);

    /**
     * 恢复已被软删除的评论，修改删除标记为未删除
     * @param commentId 评论主键ID
     * @return 受影响行数
     */
    int restoreById(@Param("commentId") Long commentId);
}
