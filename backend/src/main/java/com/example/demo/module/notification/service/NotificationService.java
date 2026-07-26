package com.example.demo.module.notification.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.notification.vo.NotificationVO;

public interface NotificationService {

    PageResult<NotificationVO> listMyNotifications(long page, long size);

    Long getMyUnreadCount();

    void markRead(Long notificationId);
}
