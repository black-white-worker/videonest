package com.videonest.module.upload.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 分片上传会话实体
 * 对应表 upload_multipart_session
 * 用来记录一次完整的大文件分片上传任务信息，对接MinIO分片上传
 */
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
