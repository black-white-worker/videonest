package com.videonest.module.notification.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.common.api.PageResult;
import com.videonest.common.exception.BusinessException;
import com.videonest.module.notification.mapper.NotificationMapper;
import com.videonest.module.notification.service.NotificationService;
import com.videonest.module.notification.vo.NotificationVO;
import com.videonest.security.LoginUser;
import com.videonest.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;

    public NotificationServiceImpl(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public PageResult<NotificationVO> listMyNotifications(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();

        IPage<NotificationVO> pageData =
                notificationMapper.selectNotificationPage(
                        new Page<>(page, size),
                        currentUser.userId()
                );

        return PageResult.of(pageData);
    }

    @Override
    public Long getMyUnreadCount() {
        Long userId = SecurityUtils.getCurrentUser().userId();
        return notificationMapper.countUnreadByRecipientId(userId);
    }

    @Override
    @Transactional
    public void markRead(Long notificationId) {
        Long userId = SecurityUtils.getCurrentUser().userId();

        int rows = notificationMapper.markReadByIdAndRecipientId(
                notificationId,
                userId
        );

        if (rows == 0) {
            throw new BusinessException(404, "通知不存在、已读或无权操作");
        }
        log.info("通知标记已读成功，notificationId={}，userId={}", notificationId, userId);
    }
}
