package com.example.demo.module.interaction.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.interaction.dto.CommentCreateRequest;
import com.example.demo.module.interaction.entity.VideoComment;
import com.example.demo.module.interaction.mapper.VideoCommentMapper;
import com.example.demo.module.interaction.service.CommentService;
import com.example.demo.module.interaction.vo.VideoCommentVO;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.mapper.VideoMapper;
import com.example.demo.module.video.service.HotRankService;
import com.example.demo.security.LoginUser;
import com.example.demo.security.SecurityUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
public class CommentServiceImpl implements CommentService {

    private static final int COMMENT_LIMIT = 5;
    private static final long COMMENT_LIMIT_SECONDS = 60;

    private final VideoMapper videoMapper;
    private final VideoCommentMapper videoCommentMapper;
    private final HotRankService hotRankService;
    private final StringRedisTemplate stringRedisTemplate;

    public CommentServiceImpl(
            VideoMapper videoMapper,
            VideoCommentMapper videoCommentMapper,
            HotRankService hotRankService,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.videoMapper = videoMapper;
        this.videoCommentMapper = videoCommentMapper;
        this.hotRankService = hotRankService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    @Transactional
    public void createComment(
            Long videoId,
            CommentCreateRequest request
    ) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        validatePublishedVideo(videoId);
        checkRateLimit(currentUser.userId());

        Long parentId = request.getParentId() == null
                ? 0L
                : request.getParentId();

        if (parentId != 0) {
            VideoComment parentComment =
                    videoCommentMapper.selectById(parentId);

            if (parentComment == null
                    || !parentComment.getVideoId().equals(videoId)
                    || parentComment.getStatus() != 1) {
                throw new BusinessException(400, "回复的评论不存在");
            }

            if (parentComment.getParentId() != 0) {
                throw new BusinessException(400, "暂不支持回复二级评论");
            }
        }

        VideoComment comment = new VideoComment();
        comment.setVideoId(videoId);
        comment.setUserId(currentUser.userId());
        comment.setParentId(parentId);
        comment.setContent(request.getContent().trim());
        comment.setStatus(1);

        videoCommentMapper.insert(comment);

        hotRankService.addCommentScore(videoId);
    }

    @Override
    public PageResult<VideoCommentVO> listComments(
            Long videoId,
            long page,
            long size
    ) {
        validatePublishedVideo(videoId);

        Page<VideoCommentVO> pageRequest =
                new Page<>(page, size);

        IPage<VideoCommentVO> pageData =
                videoCommentMapper.selectCommentPage(
                        pageRequest,
                        videoId
                );

        return PageResult.of(pageData);
    }

    @Override
    public PageResult<VideoCommentVO> listReplies(
            Long videoId,
            Long parentId,
            long page,
            long size
    ) {
        validatePublishedVideo(videoId);

        VideoComment parentComment = videoCommentMapper.selectById(parentId);
        if (parentComment == null
                || !parentComment.getVideoId().equals(videoId)
                || parentComment.getParentId() != 0
                || parentComment.getStatus() != 1) {
            throw new BusinessException(404, "一级评论不存在或已删除");
        }

        Page<VideoCommentVO> pageRequest = new Page<>(page, size);
        IPage<VideoCommentVO> pageData = videoCommentMapper.selectReplyPage(
                pageRequest, videoId, parentId);
        return PageResult.of(pageData);
    }

    @Override
    @Transactional
    public void deleteComment(Long commentId) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        if ("ADMIN".equals(currentUser.role())) {
            VideoComment comment = videoCommentMapper.selectById(commentId);

            if (comment == null || comment.getStatus() != 1) {
                throw new BusinessException(404, "评论不存在或已删除");
            }

            if (comment.getParentId() != 0) {
                throw new BusinessException(400, "管理员只能通过此接口删除一级评论");
            }

            videoCommentMapper.softDeleteById(commentId);
            videoCommentMapper.softDeleteRepliesByParentId(commentId);
            return;
        }

        int rows = videoCommentMapper.softDeleteByIdAndUserId(
                commentId,
                currentUser.userId()
        );

        if (rows == 0) {
            throw new BusinessException(
                    404,
                    "评论不存在，或你无权删除该评论"
            );
        }
    }

    private void validatePublishedVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);

        if (video == null || !"PUBLISHED".equals(video.getStatus())) {
            throw new BusinessException(
                    404,
                    "视频不存在、未发布或已下架"
            );
        }
    }

    private void checkRateLimit(Long userId) {
        String key = RedisKeys.commentRateLimit(userId);

        Long count = stringRedisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            stringRedisTemplate.expire(
                    key,
                    COMMENT_LIMIT_SECONDS,
                    TimeUnit.SECONDS
            );
        }

        if (count != null && count > COMMENT_LIMIT) {
            throw new BusinessException(
                    429,
                    "评论过于频繁，请 1 分钟后再试"
            );
        }
    }
}
