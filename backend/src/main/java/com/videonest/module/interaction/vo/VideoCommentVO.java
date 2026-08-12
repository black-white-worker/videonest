package com.videonest.module.interaction.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 前端用户端评论展示VO
 * 用于视频播放页展示评论列表，封装评论及关联用户、回复数量信息
 */
@Data
public class VideoCommentVO {

    // 评论主键ID，序列化为字符串避免前端精度丢失
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long videoId;
    private Long userId;

    // 父评论ID，0为一级根评论，非0为回复的子评论ID
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long rootId;

    private Long replyToUserId;
    private String replyToUsername;
    private String replyToNickname;

    private String content;
    private LocalDateTime createdAt;
    private String username;
    private String nickname;
    private Long replyCount;
}
