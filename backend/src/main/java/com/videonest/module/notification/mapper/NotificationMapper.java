package com.videonest.module.notification.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videonest.module.notification.entity.Notification;
import com.videonest.module.notification.vo.NotificationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知消息Mapper数据访问接口
 * 负责notification表自定义SQL查询、更新操作
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 分页查询当前用户的通知列表
     * @param page MyBatis-Plus分页分页对象，封装分页条件
     * @param recipientId 接收通知的用户ID
     * @return IPage分页封装对象，内部装载NotificationVO视图数据
     */
    IPage<NotificationVO> selectNotificationPage(
            Page<NotificationVO> page,
            @Param("recipientId") Long recipientId
    );

    /**
     * 查询指定用户未读通知的总数量
     * 用于前端展示消息小红点未读数字
     */
    @Select("""
            SELECT COUNT(1)
            FROM notification
            WHERE recipient_id = #{recipientId}
              AND is_read = 0
            """)
    Long countUnreadByRecipientId(@Param("recipientId") Long recipientId);

    /**
     * 将单条通知标记为已读
     * 增加recipient_id校验防止越权修改别人的通知
     * 只更新原本未读(is_read=0)的数据，避免重复更新
     * @return int 数据库受影响行数，1=更新成功，0=无匹配数据
     */
    @Update("""
            UPDATE notification
            SET is_read = 1
            WHERE id = #{notificationId}
              AND recipient_id = #{recipientId}
              AND is_read = 0
            """)
    int markReadByIdAndRecipientId(
            @Param("notificationId") Long notificationId,
            @Param("recipientId") Long recipientId
    );
}
