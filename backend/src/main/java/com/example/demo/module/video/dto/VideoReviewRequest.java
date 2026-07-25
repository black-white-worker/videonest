package com.example.demo.module.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VideoReviewRequest {

    /**
     * APPROVE：通过
     * REJECT：驳回
     */
    @NotBlank(message = "审核操作不能为空")
    private String action;

    /**
     * action 为 REJECT 时必填。
     */
    @Size(max = 500, message = "驳回原因不能超过 500 个字符")
    private String rejectReason;
}