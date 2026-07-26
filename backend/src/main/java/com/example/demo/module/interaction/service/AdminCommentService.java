package com.example.demo.module.interaction.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.vo.AdminCommentVO;

public interface AdminCommentService {
    PageResult<AdminCommentVO> listComments(
            long page, long size, Long videoId, String keyword);

    void deleteComment(Long commentId);
}
