package com.videonest.module.interaction.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.module.interaction.dto.CommentCreateRequest;
import com.videonest.module.interaction.entity.VideoComment;
import com.videonest.module.interaction.mapper.VideoCommentMapper;
import com.videonest.module.interaction.service.CommentService;
import com.videonest.module.interaction.vo.VideoCommentVO;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotRankService;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 前端用户评论业务实现类
 * 功能：发布评论、回复评论、分页查询评论、分页查看回复、用户/管理员删除评论、评论限流、发布消息通知事件、更新视频热度
 */
/**
 * 发布评论先获取用户 ID、校验对应视频合法性，同时通过 Redis 限制评论频率，
 * 无父评论则将 parentId 赋值为 0，有父评论时查询并校验父评论是否有效，封装评论实体存入数据库，
 * 过程中自动维护 rootId 字段用于批量删除整组嵌套评论，推送通知时会区分一级评论与回复评论的接收人，
 * 并过滤自身操作不发送通知；分页查询分为视频一级评论列表和指定一级评论下的回复列表，先做数据合法性校验，
 * 再通过 Mapper 查询数据并封装成分页结果返回；

 * 删除评论按照管理员和普通用户不同角色执行差异化 Mapper 删除逻辑，管理员仅允许删除一级评论，普通用户只能删除自己发布的评论；
 * Redis 限流通过计数器统计用户一分钟内评论次数，设置 60 秒过期时间，累计超过 5 次便抛出操作频繁异常拦截请求；新增评论方法添加事务保障数据库操作原子性，
 * 删除一级评论时会批量软删除其所有嵌套子评论，全部采用逻辑删除方式保留原始数据。
 * */
@Service
@Slf4j
public class CommentServiceImpl implements CommentService {

    private static final int COMMENT_LIMIT = 5;
    private static final long COMMENT_LIMIT_SECONDS = 60;

    private final VideoMapper videoMapper;
    private final VideoCommentMapper videoCommentMapper;
    private final HotRankService hotRankService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public CommentServiceImpl(
            VideoMapper videoMapper,
            VideoCommentMapper videoCommentMapper,
            HotRankService hotRankService,
            StringRedisTemplate stringRedisTemplate,
            ApplicationEventPublisher eventPublisher
    ) {
        this.videoMapper = videoMapper;
        this.videoCommentMapper = videoCommentMapper;
        this.hotRankService = hotRankService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 创建评论/回复评论核心方法
     * @param videoId 被评论的视频ID
     * @param request 前端提交的评论内容、父评论ID等参数
     */
    @Override
    @Transactional
    public void createComment(
            Long videoId,
            CommentCreateRequest request
    ) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        // 校验视频必须是已发布状态，未发布/下架不能评论
        validatePublishedVideo(videoId);
        // Redis校验用户评论频率，防止恶意刷屏
        checkRateLimit(currentUser.userId());

        // 处理父评论ID，如果前端没传parentId则赋值为0，代表顶级一级评论
        Long parentId = request.getParentId() == null
                ? 0L
                : request.getParentId();

        VideoComment parentComment = null;
        if (parentId != 0) {
            // 根据父评论ID查询父评论记录
            parentComment = videoCommentMapper.selectById(parentId);

            // 多重校验：父评论不存在 / 父评论不属于当前视频 / 父评论已被逻辑删除（status!=1），抛出业务异常
            if (parentComment == null
                    || !parentComment.getVideoId().equals(videoId)
                    || parentComment.getStatus() != 1) {
                throw new BusinessException(400, "回复的评论不存在");
            }

        }

        VideoComment comment = new VideoComment();
        comment.setVideoId(videoId);
        comment.setUserId(currentUser.userId());
        // 设置父评论ID，0为一级评论
        comment.setParentId(parentId);
        /*
         * 设置根评论rootId：
         * 一级评论rootId=0
         * 回复一级评论：rootId等于一级评论ID
         * 嵌套回复二级及以上评论：rootId继承最顶层根评论ID，用于批量删除整层评论
         */
        comment.setRootId(parentComment == null
                ? 0L
                : (parentComment.getParentId() == 0
                    ? parentComment.getId()
                    : parentComment.getRootId()));
        comment.setContent(request.getContent().trim());

        comment.setStatus(1);

        // 写入评论数据到数据库
        videoCommentMapper.insert(comment);
        hotRankService.addCommentScore(videoId);

        // 查询视频信息拿到作者ID
        Video video = videoMapper.selectById(videoId);
        // 确定消息接收人：一级评论通知视频作者，回复评论通知被回复的评论发布人
        Long recipientId = parentId == 0
                ? video.getAuthorId()
                : parentComment.getUserId();
        // 定义消息类型：COMMENT=一级评论，REPLY=回复评论
        String type = parentId == 0 ? "COMMENT" : "REPLY";

        // 避免自己评论自己、自己回复自己，不需要推送通知
        if (!recipientId.equals(currentUser.userId())) {
            eventPublisher.publishEvent(
                    new NotificationDomainEvent(
                            new NotificationEvent(
                                    UUID.randomUUID().toString(),
                                    recipientId,
                                    currentUser.userId(),
                                    type,
                                    videoId,
                                    comment.getId(),
                                    comment.getContent()
                            )
                    )
            );
        }
        log.info(
                "评论创建成功，commentId={}，videoId={}，userId={}，type={}",
                comment.getId(),
                videoId,
                currentUser.userId(),
                type
        );
    }

    /**
     * 分页查询视频下所有一级评论
     * @param videoId 视频ID
     * @param page 当前页码
     * @param size 每页条数
     * @return 包装后的分页结果VO
     */
    @Override
    public PageResult<VideoCommentVO> listComments(
            Long videoId,
            long page,
            long size
    ) {
        // 校验合法
        validatePublishedVideo(videoId);

        // 用户信息和回复数由一条分页 SQL 批量带回，避免每条评论各查两次的 N+1。
        Page<VideoCommentVO> pageRequest = new Page<>(page, size);
        // 调用Mapper自定义分页SQL查询一级评论，封装为统一分页返回体PageResult
        return PageResult.of(
                videoCommentMapper.selectCommentPage(pageRequest, videoId)
        );
    }

    /**
     * 分页查询某一条一级评论下的所有回复子评论
     * @param videoId 视频ID
     * @param parentId 父级一级评论ID
     * @param page 页码
     * @param size 页大小
     * @return 回复评论分页VO
     */
    @Override
    public PageResult<VideoCommentVO> listReplies(
            Long videoId,
            Long parentId,
            long page,
            long size
    ) {
        validatePublishedVideo(videoId);

        // 查询要展开回复的父评论
        VideoComment parentComment = videoCommentMapper.selectById(parentId);
        if (parentComment == null
                || !parentComment.getVideoId().equals(videoId)
                || parentComment.getParentId() != 0
                || parentComment.getStatus() != 1) {
            throw new BusinessException(404, "一级评论不存在或已删除");
        }

        // 构建分页参数
        Page<VideoCommentVO> pageRequest = new Page<>(page, size);
        // Mapper执行自定义SQL分页查询该父评论下所有回复
        IPage<VideoCommentVO> pageData = videoCommentMapper.selectReplyPage(
                pageRequest, videoId, parentId);
        return PageResult.of(pageData);
    }

    /**
     * 删除评论接口，区分管理员权限和普通用户权限
     * @param commentId 待删除评论ID
     */
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
            // 根据根评论ID批量逻辑删除该一级评论下所有嵌套回复
            videoCommentMapper.softDeleteRepliesByRootId(commentId);
            log.info("管理员删除一级评论及回复成功，commentId={}", commentId);
            return;
        }

        // 普通用户删除
        VideoComment comment = videoCommentMapper.selectById(commentId);
        // 调用Mapper条件删除：仅当评论ID+本人用户ID匹配才执行逻辑删除，防止越权删别人评论
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
        if (comment != null && comment.getParentId() == 0) {
            videoCommentMapper.softDeleteRepliesByRootId(commentId);
        }
        log.info("用户删除评论成功，commentId={}，userId={}", commentId, currentUser.userId());
    }

    /**
     * 校验视频是否存在且为已发布状态
     * @param videoId 视频主键ID
     */
    private void validatePublishedVideo(Long videoId) {
        Video video = videoMapper.selectById(videoId);

        if (video == null || !"PUBLISHED".equals(video.getStatus())) {
            throw new BusinessException(
                    404,
                    "视频不存在、未发布或已下架"
            );
        }
    }

    /**
     * 私有工具方法：Redis实现用户评论一分钟N次限流
     * @param userId 当前操作用户ID
     */
    /**
     * 60 秒就是限流时间窗口，在这整整 60 秒的区间里，统计 count 评论次数，
     * 超过 5 条就拦截；60 秒到点直接清零，重新开始一轮统计
     * */
    private void checkRateLimit(Long userId) {
        String key = RedisKeys.commentRateLimit(userId);

        // Redis自增指令：key不存在默认初始化为1，之后每次调用+1，返回当前计数值
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
