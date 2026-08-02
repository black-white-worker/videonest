package com.example.demo.module.upload.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UploadPresignRequest {

    @NotBlank
    @Pattern(regexp = "cover|video", message = "上传类型只支持 cover 或 video")
    private String type;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @NotBlank
    @Size(max = 100)
    private String contentType;

    @Min(1)
    @Max(524288000)
    private long size;
}
