package com.example.demo.module.interaction.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.module.interaction.entity.VideoComment;
import com.example.demo.module.interaction.mapper.AdminCommentMapper;
import com.example.demo.module.interaction.mapper.VideoCommentMapper;
import com.example.demo.module.interaction.service.AdminCommentService;
import com.example.demo.module.interaction.vo.AdminCommentVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    public PageResult<AdminCommentVO> listComments(
            long page, long size, Long videoId, String keyword, Integer status) {
        IPage<AdminCommentVO> pageData = adminCommentMapper.selectAdminCommentPage(
                new Page<>(page, size),
                videoId,
                StringUtils.hasText(keyword) ? keyword.trim() : null,
                status
        );
        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        VideoComment comment = videoCommentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 1) {
            throw new BusinessException(404, "评论不存在或已删除");
        }

        videoCommentMapper.softDeleteById(commentId);
        if (comment.getParentId() == 0) {
            videoCommentMapper.softDeleteRepliesByParentId(commentId);
        }
        log.info("管理员软删除评论成功，commentId={}", commentId);
    }

    @Override
    @Transactional
    public void restoreComment(Long commentId) {
        VideoComment comment = videoCommentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 0) {
            throw new BusinessException(404, "评论不存在或未被删除");
        }

        if (videoCommentMapper.restoreById(commentId) == 0) {
            throw new BusinessException(400, "评论恢复失败");
        }
        log.info("管理员恢复评论成功，commentId={}", commentId);
    }
}
