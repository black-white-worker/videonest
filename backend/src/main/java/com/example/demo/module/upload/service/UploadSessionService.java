package com.example.demo.module.upload.service;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.StorageOperationException;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.oss.service.StoredObjectMetadata;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.upload.dto.UploadPresignRequest;
import com.example.demo.module.upload.vo.FileUploadVO;
import com.example.demo.module.upload.vo.UploadPresignVO;
import com.example.demo.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class UploadSessionService {

    private static final long MAX_COVER_SIZE = 10 * 1024 * 1024L;
    private static final long MAX_VIDEO_SIZE = 500 * 1024 * 1024L;
    private static final int PRESIGN_MINUTES = 15;

    private final MinioService minioService;
    private final UploadedFileSecurityValidator securityValidator;
    private final RedisTemplate<String, Object> redisTemplate;

    public UploadSessionService(
            MinioService minioService,
            UploadedFileSecurityValidator securityValidator,
            RedisTemplate<String, Object> redisTemplate
    ) {
        this.minioService = minioService;
        this.securityValidator = securityValidator;
        this.redisTemplate = redisTemplate;
    }

    public UploadPresignVO issue(UploadPresignRequest request) {
        long userId = SecurityUtils.getCurrentUser().userId();
        validateDeclaredMetadata(request);

        String uploadId = UUID.randomUUID().toString();
        String extension = canonicalExtension(request.getType(), request.getContentType());
        String objectId = UUID.randomUUID().toString();
        String stagingObjectName = "staging/%d/%s/%s.%s".formatted(
                userId, LocalDate.now(), objectId, extension
        );
        String finalObjectName = "%s/%d/%s/%s.%s".formatted(
                request.getType(), userId, LocalDate.now(), objectId, extension
        );
        UploadTicket ticket = new UploadTicket(
                uploadId, userId, request.getType(), stagingObjectName,
                finalObjectName, request.getSize()
        );
        redisTemplate.opsForValue().set(
                RedisKeys.uploadTicket(uploadId),
                ticket,
                PRESIGN_MINUTES + 5L,
                TimeUnit.MINUTES
        );

        return new UploadPresignVO(
                uploadId,
                stagingObjectName,
                minioService.createPresignedPutUrl(stagingObjectName, PRESIGN_MINUTES),
                "PUT",
                Map.of("Content-Type", request.getContentType()),
                PRESIGN_MINUTES * 60
        );
    }

    public FileUploadVO complete(String uploadId) {
        long userId = SecurityUtils.getCurrentUser().userId();
        Object value = redisTemplate.opsForValue().get(RedisKeys.uploadTicket(uploadId));
        if (!(value instanceof UploadTicket ticket) || ticket.userId() != userId) {
            throw new BusinessException(404, "上传凭证不存在、已过期或不属于当前用户");
        }

        try {
            StoredObjectMetadata metadata = minioService.statObject(
                    ticket.stagingObjectName()
            );
            if (metadata.size() <= 0 || metadata.size() != ticket.declaredSize()) {
                throw new BusinessException(400, "对象实际大小与上传凭证不一致");
            }
            long maxSize = "cover".equals(ticket.type())
                    ? MAX_COVER_SIZE : MAX_VIDEO_SIZE;
            if (metadata.size() > maxSize) {
                throw new BusinessException(413, "上传对象超过大小限制");
            }

            UploadedFileSecurityValidator.Inspection inspection =
                    securityValidator.inspect(ticket.stagingObjectName(), ticket.type());
            registerConfirmed(ticket.finalObjectName(), userId, ticket.type());
            minioService.moveObject(
                    ticket.stagingObjectName(), ticket.finalObjectName()
            );
            redisTemplate.delete(RedisKeys.uploadTicket(uploadId));
            return new FileUploadVO(
                    ticket.finalObjectName(),
                    inspection.durationSeconds()
            );
        } catch (RuntimeException e) {
            if (shouldDiscardObject(e)) {
                // 确定为非法的对象立即清理；存储/扫描服务短暂故障则保留票据供重试。
                markConsumed(ticket.finalObjectName());
                try {
                    minioService.deleteObject(ticket.stagingObjectName());
                } catch (RuntimeException cleanupError) {
                    e.addSuppressed(cleanupError);
                    log.warn("清理未通过校验的上传对象失败，objectName={}", ticket.stagingObjectName(), cleanupError);
                }
                redisTemplate.delete(RedisKeys.uploadTicket(uploadId));
            }
            throw e;
        }
    }

    public void registerConfirmed(String objectName, long userId, String type) {
        redisTemplate.opsForValue().set(
                RedisKeys.confirmedUpload(objectName),
                userId + ":" + type,
                2,
                TimeUnit.HOURS
        );
    }

    public void assertConfirmed(String objectName, long userId, String type) {
        Object marker = redisTemplate.opsForValue().get(
                RedisKeys.confirmedUpload(objectName)
        );
        if (!(userId + ":" + type).equals(marker)) {
            throw new BusinessException(400, "上传对象未完成校验、已过期或不属于当前用户");
        }
    }

    public void markConsumed(String objectName) {
        if (objectName != null && !objectName.isBlank()) {
            try {
                redisTemplate.delete(RedisKeys.confirmedUpload(objectName));
            } catch (RuntimeException e) {
                // 数据库唯一索引仍会阻止复用；确认标记也会在两小时后自动过期。
                log.warn("清理已消费上传标记失败，objectName={}", objectName, e);
            }
        }
    }

    private void validateDeclaredMetadata(UploadPresignRequest request) {
        long max = "cover".equals(request.getType()) ? MAX_COVER_SIZE : MAX_VIDEO_SIZE;
        if (request.getSize() > max) {
            throw new BusinessException(413, "文件超过允许大小");
        }
        canonicalExtension(request.getType(), request.getContentType());
    }

    private String canonicalExtension(String type, String contentType) {
        return switch (type + ":" + contentType.toLowerCase()) {
            case "cover:image/jpeg" -> "jpg";
            case "cover:image/png" -> "png";
            case "video:video/mp4" -> "mp4";
            default -> throw new BusinessException(400, "声明的媒体类型不受支持");
        };
    }

    private boolean shouldDiscardObject(RuntimeException error) {
        if (error instanceof StorageOperationException storageError) {
            return !storageError.isRetryable();
        }
        if (error instanceof BusinessException businessError) {
            return businessError.getCode() < 500;
        }
        return false;
    }

}
