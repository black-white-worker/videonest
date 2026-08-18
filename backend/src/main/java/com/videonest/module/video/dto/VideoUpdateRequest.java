package com.videonest.module.video.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO：视频编辑更新请求
 * 用户修改已存在视频的元信息（分区、标题、简介、封面等）
 */
@Data
public class VideoUpdateRequest {

    @NotNull(message = "请选择视频分区")
    @Min(value = 1, message = "分区 ID 不合法")
    private Long categoryId;

    @NotBlank(message = "视频标题不能为空")
    @Size(max = 100, message = "视频标题不能超过 100 个字符")
    private String title;

    @Size(max = 2000, message = "视频简介不能超过 2000 个字符")
    private String description;

    // MinIO对象存储封面对象名
    private String coverObjectName;

    private String videoObjectName;

    @NotNull(message = "请填写视频时长")
    @Min(value = 1, message = "视频时长必须大于 0 秒")
    private Integer duration;
}
