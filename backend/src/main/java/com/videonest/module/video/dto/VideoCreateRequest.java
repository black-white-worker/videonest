package com.videonest.module.video.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO：创建视频请求实体
 * 接收前端提交创建视频的表单参数，配合Spring Validation做请求参数校验
 */
@Data
public class VideoCreateRequest {

    @NotNull(message = "请选择视频分区")
    @Min(value = 1, message = "分区 ID 不合法")
    private Long categoryId;

    @NotBlank(message = "视频标题不能为空")
    @Size(max = 100, message = "视频标题不能超过 100 个字符")
    private String title;

    @Size(max = 2000, message = "视频简介不能超过 2000 个字符")
    private String description;

    private String coverObjectName;

    @NotBlank(message = "请上传视频文件")
    private String videoObjectName;

    @NotNull(message = "请填写视频时长")
    @Min(value = 1, message = "视频时长必须大于 0 秒")
    private Integer duration;
}
