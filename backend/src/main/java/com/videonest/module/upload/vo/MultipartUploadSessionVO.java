package com.videonest.module.upload.vo;

import java.time.LocalDateTime;
import java.util.List;

public record MultipartUploadSessionVO(String sessionId, String status, long partSize,
                                       int totalParts, LocalDateTime expiresAt,
                                       List<MultipartPartVO> uploadedParts) { }
