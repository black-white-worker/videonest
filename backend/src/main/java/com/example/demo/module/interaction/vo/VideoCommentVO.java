package com.example.demo.module.interaction.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoCommentVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long videoId;
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    private String content;
    private LocalDateTime createdAt;
    private String username;
    private String nickname;
    private Long replyCount;
}
