package com.videonest.module.interaction.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.interaction.entity.VideoComment;
import com.videonest.module.interaction.mapper.AdminCommentMapper;
import com.videonest.module.interaction.mapper.VideoCommentMapper;
import com.videonest.module.interaction.service.AdminCommentService;
import com.videonest.module.interaction.vo.AdminCommentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理员端评论管理业务层实现类
 * 实现后台对视频评论的分页查询、软删除、恢复删除评论功能
 */
@Service
@Slf4j
public class AdminCommentServiceImpl implements AdminCommentService {
    private final AdminCommentMapper adminCommentMapper;
    private final VideoCommentMapper videoCommentMapper;

    public AdminCommentServiceImpl(AdminCommentMapper adminCommentMapper,
                                   VideoCommentMapper videoCommentMapper) {
        this.adminCommentMapper = adminCommentMapper;
        this.videoCommentMapper = videoCommentMapper;
    }

    /**
     * 管理员分页查询评论列表
     * @param page 当前页码
     * @param size 每页条数
     * @param videoId 视频ID（筛选条件）
     * @param keyword 关键词（评论内容模糊搜索）
     * @param status 评论状态 0已删除 1正常
     * @return 统一分页返回结果 PageResult封装AdminCommentVO视图对象
     */
    @Override
    public PageResult<AdminCommentVO> listComments(
            long page, long size, Long videoId, String keyword, Integer status) {
        IPage<AdminCommentVO> pageData = adminCommentMapper.selectAdminCommentPage(
                new Page<>(page, size),
                videoId,
                // 使用Spring工具类判断关键词是否有有效文本，有则去除首尾空格，无则传null避免SQL查询多余条件
                StringUtils.hasText(keyword) ? keyword.trim() : null,
                status
        );
        return PageResult.of(pageData);
    }

    /**
     * 管理员软删除评论
     * 主评论删除时级联软删除其所有子回复评论
     * @param commentId 待删除评论主键ID
     */
    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        VideoComment comment = videoCommentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 1) {
            throw new BusinessException(404, "评论不存在或已删除");
        }

        videoCommentMapper.softDeleteById(commentId);
        if (comment.getParentId() == 0) {
            // 判断是否为主评论：parentId=0代表一级根评论，需要连带删除所有下级回复
            videoCommentMapper.softDeleteRepliesByRootId(commentId);
        }
        log.info("管理员软删除评论成功，commentId={}", commentId);
    }

    /**
     * 管理员恢复已软删除的评论
     * @param commentId 待恢复评论主键ID
     */
    @Override
    @Transactional
    public void restoreComment(Long commentId) {
        VideoComment comment = videoCommentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 0) {
            throw new BusinessException(404, "评论不存在或未被删除");
        }

        if (comment.getParentId() != 0) {
            VideoComment rootComment = videoCommentMapper.selectById(comment.getRootId());
            if (rootComment == null || rootComment.getStatus() != 1) {
                throw new BusinessException(400, "请先恢复该回复所属的一级评论");
            }
        }

        if (videoCommentMapper.restoreById(commentId) == 0) {
            throw new BusinessException(400, "评论恢复失败");
        }
        if (comment.getParentId() == 0) {
            videoCommentMapper.restoreRepliesByRootId(commentId);
        }
        log.info("管理员恢复评论成功，commentId={}", commentId);
    }
}
