package com.videonest.module.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * PasswordResetRequestVO 返回数据对象。
 */
@Data
@AllArgsConstructor
public class PasswordResetRequestVO {
    private Long id;
    private Long userId;
    private String username;
    private String nickname;
    private String status;
    private LocalDateTime createTime;
}
