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

@Service
@Slf4j
public class VideoReviewServiceImpl implements VideoReviewService {

    private final VideoMapper videoMapper;
    private final MinioService minioService;
    private final HotVideoCacheService hotVideoCacheService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public VideoReviewServiceImpl(
            VideoMapper videoMapper,
            MinioService minioService,
            HotVideoCacheService hotVideoCacheService,
            ApplicationEventPublisher applicationEventPublisher
    ) {
        this.videoMapper = videoMapper;
        this.minioService = minioService;
        this.hotVideoCacheService = hotVideoCacheService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public PageResult<AdminVideoReviewVO> listPendingReviewVideos(long page, long size) {
        Page<AdminVideoReviewVO> pageRequest = new Page<>(page, size);
        IPage<AdminVideoReviewVO> pageData = videoMapper.selectPendingReviewPage(pageRequest);
        pageData.getRecords().forEach(video -> {
            video.setCoverUrl(minioService.getAccessUrl(video.getCoverUrl()));
            video.setVideoUrl(minioService.getAccessUrl(video.getVideoUrl()));
        });
        return PageResult.of(pageData);
    }

    @Transactional
    @Override
    public void reviewVideo(Long videoId, String action, String rejectReason) {
        Video reviewedVideo = videoMapper.selectById(videoId);
        String status;
        String finalRejectReason = null;

        if ("APPROVE".equalsIgnoreCase(action)) {
            status = "PUBLISHED";
        } else if ("REJECT".equalsIgnoreCase(action)) {
            status = "REJECTED";
            if (!StringUtils.hasText(rejectReason)) {
                throw new BusinessException(400, "驳回投稿时必须填写驳回原因");
            }
            finalRejectReason = rejectReason.trim();
        } else {
            throw new BusinessException(400, "审核操作只能是 APPROVE 或 REJECT");
        }

        int affectedRows = videoMapper.reviewVideo(videoId, status, finalRejectReason);
        if (affectedRows == 0) {
            throw new BusinessException(400, "视频不存在，或该投稿已经完成审核");
        }
        invalidateHotVideoCardsAfterCommit();
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

    private void invalidateHotVideoCardsAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hotVideoCacheService.invalidateCards();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        hotVideoCacheService.invalidateCards();
                    }
                }
        );
    }
}
