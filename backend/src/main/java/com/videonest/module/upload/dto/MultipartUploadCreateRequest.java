package com.videonest.module.upload.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MultipartUploadCreateRequest {
    @NotBlank @Pattern(regexp = "video")
    private String type;
    @NotBlank @Size(max = 255)
    private String fileName;
    @NotBlank @Size(max = 100)
    private String contentType;
    @Min(1) @Max(838860800)
    private long size;
    @NotBlank @Size(max = 512)
    private String fingerprint;
}
