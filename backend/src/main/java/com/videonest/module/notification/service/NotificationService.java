package com.videonest.module.notification.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.notification.vo.NotificationVO;

public interface NotificationService {

    /**
     * 分页查询当前登录用户自己的通知列表
     * @param page 当前页码
     * @param size 每页展示条数
     * @return 统一分页结果，内部包含分页总条数、当前页数据列表（NotificationVO）
     */
    PageResult<NotificationVO> listMyNotifications(long page, long size);

    /**
     * 查询当前登录用户的未读通知总数
     * 用于前端消息图标右上角未读数字红点展示
     * @return 未读通知数量
     */
    Long getMyUnreadCount();

    /**
     * 将指定单条通知标记为已读状态
     * @param notificationId 通知主键ID
     */
    void markRead(Long notificationId);
}
