package com.example.demo.module.interaction.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.dto.CommentCreateRequest;
import com.example.demo.module.interaction.vo.VideoCommentVO;

public interface CommentService {

    void createComment(Long videoId, CommentCreateRequest request);

    PageResult<VideoCommentVO> listComments(
            Long videoId,
            long page,
            long size
    );

    PageResult<VideoCommentVO> listReplies(
            Long videoId,
            Long parentId,
            long page,
            long size
    );

    void deleteComment(Long commentId);
}
