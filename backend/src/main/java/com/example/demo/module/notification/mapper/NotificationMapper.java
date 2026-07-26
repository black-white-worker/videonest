package com.example.demo.module.notification.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.module.notification.entity.Notification;
import com.example.demo.module.notification.vo.NotificationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    IPage<NotificationVO> selectNotificationPage(
            Page<NotificationVO> page,
            @Param("recipientId") Long recipientId
    );

    @Select("""
            SELECT COUNT(1)
            FROM notification
            WHERE recipient_id = #{recipientId}
              AND is_read = 0
            """)
    Long countUnreadByRecipientId(@Param("recipientId") Long recipientId);

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
