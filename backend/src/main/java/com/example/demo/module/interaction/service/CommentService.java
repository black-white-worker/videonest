package com.example.demo.module.interaction.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.interaction.dto.CommentCreateRequest;
import com.example.demo.module.interaction.vo.VideoCommentVO;

/**
 * 普通用户端 视频评论业务层接口
 * 定义用户发表评论、查看评论、查看回复、删除评论的业务抽象方法
 * 具体业务逻辑在 CommentServiceImpl 实现类中编写
 */
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
