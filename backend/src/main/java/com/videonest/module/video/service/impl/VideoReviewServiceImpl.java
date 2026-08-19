package com.videonest.module.video.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.module.notification.event.NotificationDomainEvent;
import com.videonest.module.notification.event.NotificationEvent;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.mapper.VideoMapper;
import com.videonest.module.video.service.HotVideoCacheService;
import com.videonest.module.video.service.VideoReviewService;
import com.videonest.module.video.service.VideoListCacheService;
import com.videonest.module.video.vo.AdminVideoReviewVO;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 理员视频审核业务实现
 * 查询待审核分页列表、审核通过 / 驳回视频、事务处理、缓存失效、发布通知事件。
 * */
@Service
@Slf4j
public class VideoReviewServiceImpl implements VideoReviewService {

    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final HotVideoCacheService hotVideoCacheService;
    private final VideoListCacheService videoListCacheService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public VideoReviewServiceImpl(
            VideoMapper videoMapper,
            MinioService minioService,
            HotVideoCacheService hotVideoCacheService,
            VideoListCacheService videoListCacheService,
            ApplicationEventPublisher applicationEventPublisher
    ) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.hotVideoCacheService = hotVideoCacheService;
        this.videoListCacheService = videoListCacheService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 查询待审核视频分页列表
     * @param page 当前页码
     * @param size 每页条数
     * @return 分页封装结果，返回给管理端
     */
    @Override
    public PageResult<AdminVideoReviewVO> listPendingReviewVideos(long page, long size) {
        Page<AdminVideoReviewVO> pageRequest = new Page<>(page, size);
        IPage<AdminVideoReviewVO> pageData = videoMapper.selectPendingReviewPage(pageRequest);
        // 遍历分页结果集合，把数据库中存储的minio文件key，转换为可直接浏览器访问的完整url
        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            video.setVideoUrl(minioService.getAccessUrl(video.getVideoUrl()));
        });
        return PageResult.of(pageData);
    }

    /**
     * 视频审核操作：通过 / 驳回
     * @ Transactional 当前方法开启数据库事务，方法抛出异常自动回滚
     * @param videoId 需要审核的视频id
     * @param action 审核动作 APPROVE 通过 / REJECT 驳回
     * @param rejectReason 驳回原因，驳回时必填，通过该参数可为null
     */
    @Transactional
    @Override
    public void reviewVideo(Long videoId, String action, String rejectReason) {
        Video reviewedVideo = videoMapper.selectById(videoId);
        String status;
        String finalRejectReason = null;

        // 审核通过
        if ("APPROVE".equalsIgnoreCase(action)) {
            status = "PUBLISHED";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            // 驳回视频
            status = "REJECTED";
            if (!StringUtils.hasText(rejectReason)) {
                throw new BusinessException(400, "驳回投稿时必须填写驳回原因");
            }
            finalRejectReason = rejectReason.trim();
        } else {
            throw new BusinessException(400, "审核操作只能是 APPROVE 或 REJECT");
        }

        /*
         * 执行自定义mapper更新：更新视频状态、驳回原因
         * 返回受影响行数；如果视频已经审核过，数据库不会更新任何行，返回0
         */
        int affectedRows = videoMapper.reviewVideo(videoId, status, finalRejectReason);
        if (affectedRows == 0) {
            throw new BusinessException(400, "视频不存在，或该投稿已经完成审核");
        }
        // 事务提交之后再失效热门视频缓存，避免事务未提交缓存就先删导致脏读
        invalidateHotVideoCardsAfterCommit();

        // 如果是驳回操作，发布通知事件，通知视频作者投稿被驳回
        if ("REJECTED".equals(status)) {
            LoginUser admin = SecurityUtils.getCurrentUser();
            applicationEventPublisher.publishEvent(
                    new NotificationDomainEvent(
                            new NotificationEvent(
                                    UUID.randomUUID().toString(),
                                    reviewedVideo.getAuthorId(),
                                    admin.userId(),
                                    "VIDEO_REJECTED",
                                    videoId,
                                    null,
                                    finalRejectReason
                            )
                    )
            );
            log.info(
                    "视频驳回通知事件发布成功，videoId={}，authorId={}，adminId={}",
                    videoId, reviewedVideo.getAuthorId(), admin.userId()
            );
        }
        log.info("管理员审核视频成功，videoId={}，action={}，status={}",
                videoId, action, status);
    }

    /**
     * 事务提交后执行缓存失效操作
     * 核心目的：必须等数据库事务真正提交成功之后，才去删除Redis缓存
     * 防止事务还没提交，缓存先删除，并发请求读取旧缓存数据产生脏数据
     */
    private void invalidateHotVideoCardsAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hotVideoCacheService.invalidateCards();
            videoListCacheService.invalidateAll();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        hotVideoCacheService.invalidateCards();
                        videoListCacheService.invalidateAll();
                    }
                }
        );
    }
}
