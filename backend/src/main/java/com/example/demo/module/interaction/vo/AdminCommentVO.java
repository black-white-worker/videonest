package com.example.demo.module.interaction.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台管理端评论返回VO
 * 用于管理员后台查询评论列表时，返回完整关联信息给前端页面展示
 */
@Data
public class AdminCommentVO {

    // 评论主键ID，转为字符串返回前端，避免大Long值JS精度丢失
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long videoId;
    private String videoTitle;
    private Long userId;
    private String username;
    private String nickname;

    // 父评论ID，同样转字符串防止精度丢失，区分一级评论和回复评论
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long rootId;

    private String content;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
