package com.example.demo.module.upload.vo;

import java.util.Map;

public record UploadPresignVO(
        String uploadId,
        String objectName,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        int expiresInSeconds
) {
}
