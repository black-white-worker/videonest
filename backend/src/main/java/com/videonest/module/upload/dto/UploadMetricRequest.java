package com.videonest.module.upload.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UploadMetricRequest {
    @NotBlank @Pattern(regexp = "single|multipart") private String mode;
    @NotBlank @Pattern(regexp = "started|put_succeeded|put_failed|complete_succeeded|complete_failed") private String event;
    @Min(1) @Max(838860800) private long size;
    @Min(0) private long durationMs;
    @Pattern(regexp = "|network|timeout|storage_4xx|storage_5xx|presign_expired|validation|unknown") private String reason = "";
}
