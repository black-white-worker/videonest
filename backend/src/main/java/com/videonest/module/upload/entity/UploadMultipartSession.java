package com.videonest.module.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("upload_multipart_session")
public class UploadMultipartSession {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long userId;
    private String type;
    private String fileName;
    private String contentType;
    private Long declaredSize;
    private String fingerprint;
    private Long partSize;
    private Integer totalParts;
    private String stagingObjectName;
    private String minioUploadId;
    private String status;
    private String finalObjectName;
    private Integer detectedDuration;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
