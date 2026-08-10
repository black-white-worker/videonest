package com.example.demo.module.interaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布评论请求DTO
 * 接收前端提交新增评论时传递的参数
 */
@Data
public class CommentCreateRequest {

    private Long parentId = 0L;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容不能超过 500 个字符")
    private String content;
}