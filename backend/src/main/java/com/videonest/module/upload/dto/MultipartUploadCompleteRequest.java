package com.videonest.module.upload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class MultipartUploadCompleteRequest {
    @NotEmpty @Valid
    private List<Part> parts;
    @Data
    public static class Part {
        @Min(1) @Max(10000) private int partNumber;
        @NotBlank private String etag;
    }
}
