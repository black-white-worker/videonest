package com.videonest.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户密码重置申请持久化对象。
 */
@Data
@TableName("password_reset_request")
public class PasswordResetRequest {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String status;
    private Long handledBy;
    private LocalDateTime handledAt;
    private LocalDateTime createTime;
}
