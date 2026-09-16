package com.videonest.module.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * AdminUserVO 返回数据对象。
 */
@Data
@AllArgsConstructor
public class AdminUserVO {
    private Long id;
    private String username;
    private String nickname;
    private Integer status;
    private String role;
}
