package com.example.demo.module.interaction.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminCommentVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private Long videoId;
    private String videoTitle;
    private Long userId;
    private String username;
    private String nickname;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    private String content;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
