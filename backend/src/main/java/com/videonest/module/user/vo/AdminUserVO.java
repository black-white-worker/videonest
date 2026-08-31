package com.videonest.module.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminUserVO {
    private Long id;
    private String username;
    private String nickname;
    private Integer status;
    private String role;
}
