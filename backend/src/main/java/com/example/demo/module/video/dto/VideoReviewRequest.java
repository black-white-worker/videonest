package com.example.demo.module.video.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VideoReviewRequest {

    /**
     * APPROVE：通过
     * REJECT：驳回
     */
    @NotBlank(message = "审核操作不能为空")
    private String action;
}