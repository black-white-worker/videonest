package com.example.demo.module.upload.service;

public record UploadTicket(
        String uploadId,
        long userId,
        String type,
        String stagingObjectName,
        String finalObjectName,
        long declaredSize
) {
}
