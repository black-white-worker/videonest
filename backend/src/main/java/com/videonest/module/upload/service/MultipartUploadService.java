package com.videonest.module.upload.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videonest.common.exception.BusinessException;
import com.videonest.infrastructure.oss.service.MinioService;
import com.videonest.infrastructure.redis.RedisKeys;
import com.videonest.infrastructure.redis.RenewableRedisLock;
import com.videonest.module.upload.config.UploadProperties;
import com.videonest.module.upload.dto.MultipartUploadCompleteRequest;
import com.videonest.module.upload.dto.MultipartUploadCreateRequest;
import com.videonest.module.upload.entity.UploadMultipartSession;
import com.videonest.module.upload.mapper.UploadMultipartSessionMapper;
import com.videonest.module.upload.vo.FileUploadVO;
import com.videonest.module.upload.vo.MultipartPartPresignVO;
import com.videonest.module.upload.vo.MultipartPartVO;
import com.videonest.module.upload.vo.MultipartUploadSessionVO;
import com.videonest.security.SecurityUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MultipartUploadService {
    private static final String UPLOADING = "UPLOADING";
    private static final String COMPLETED = "COMPLETED";
    private final UploadMultipartSessionMapper mapper;
    private final MinioService minio;
    private final UploadProperties properties;
    private final RedisTemplate<String, Object> redis;
    private final UploadSessionService uploadSessionService;
    private final RenewableRedisLock locks;

    public MultipartUploadService(UploadMultipartSessionMapper mapper, MinioService minio, UploadProperties properties,
                                  RedisTemplate<String, Object> redis, UploadSessionService uploadSessionService,
                                  RenewableRedisLock locks) {
        this.mapper = mapper; this.minio = minio; this.properties = properties; this.redis = redis;
        this.uploadSessionService = uploadSessionService; this.locks = locks;
    }

    public MultipartUploadSessionVO create(MultipartUploadCreateRequest request) {
        long userId = SecurityUtils.getCurrentUser().userId();
        validate(request.getSize(), request.getContentType());
        ensureDiskHeadroom();
        long active = mapper.selectCount(new LambdaQueryWrapper<UploadMultipartSession>()
                .eq(UploadMultipartSession::getUserId, userId).eq(UploadMultipartSession::getStatus, UPLOADING)
                .gt(UploadMultipartSession::getExpiresAt, LocalDateTime.now()));
        if (active >= properties.getMaxActiveMultipartSessionsPerUser()) {
            throw new BusinessException(429, "已有大文件正在上传，请完成或取消后再试");
        }
        String id = UUID.randomUUID().toString();
        String objectId = UUID.randomUUID().toString();
        String staging = "staging/%d/%s/%s.mp4".formatted(userId, LocalDate.now(), objectId);
        long partSize = properties.getMultipartPartSizeBytes();
        int totalParts = Math.toIntExact((request.getSize() + partSize - 1) / partSize);
        UploadMultipartSession session = new UploadMultipartSession();
        session.setId(id); session.setUserId(userId); session.setType("video"); session.setFileName(request.getFileName());
        session.setContentType(request.getContentType()); session.setDeclaredSize(request.getSize());
        session.setFingerprint(request.getFingerprint()); session.setPartSize(partSize); session.setTotalParts(totalParts);
        session.setStagingObjectName(staging); session.setMinioUploadId(id);
        session.setStatus(UPLOADING); session.setExpiresAt(LocalDateTime.now().plusHours(properties.getMultipartSessionHours()));
        mapper.insert(session);
        return view(session, List.of());
    }

    public MultipartUploadSessionVO status(String id) {
        UploadMultipartSession session = owned(id);
        expireIfNeeded(session);
        return view(session, UPLOADING.equals(session.getStatus()) ? parts(session) : List.of());
    }

    public MultipartPartPresignVO presignPart(String id, int partNumber) {
        UploadMultipartSession session = owned(id);
        ensureUploading(session);
        if (partNumber < 1 || partNumber > session.getTotalParts()) throw new BusinessException(400, "分片编号不合法");
        return new MultipartPartPresignVO(partNumber,
                minio.createPresignedPartUploadUrl(partObject(session, partNumber), partNumber,
                        properties.getMultipartUrlMinutes()),
                Map.of("Content-Type", session.getContentType()), properties.getMultipartUrlMinutes() * 60);
    }

    public FileUploadVO complete(String id, MultipartUploadCompleteRequest request) {
        UploadMultipartSession session = owned(id);
        if (COMPLETED.equals(session.getStatus())) return new FileUploadVO(session.getFinalObjectName(), session.getDetectedDuration());
        ensureUploading(session);
        var lock = locks.tryAcquire("videonest:lock:multipart-complete:" + id, 10, TimeUnit.MINUTES);
        if (lock.isEmpty()) throw new BusinessException(409, "该上传正在完成，请勿重复提交");
        try (var ignored = lock.get()) {
            session = owned(id);
            if (COMPLETED.equals(session.getStatus())) return new FileUploadVO(session.getFinalObjectName(), session.getDetectedDuration());
            if (request.getParts().size() != session.getTotalParts() || parts(session).size() != session.getTotalParts()) {
                throw new BusinessException(400, "分片尚未全部完成或上传结果不一致");
            }
            minio.composeObjects(session.getStagingObjectName(), partObjects(session));
            minio.deleteObjects(partObjects(session));
            String finalName = "video/%d/%s/%s.mp4".formatted(session.getUserId(), LocalDate.now(), UUID.randomUUID());
            redis.opsForValue().set(RedisKeys.uploadTicket(id), new UploadTicket(id, session.getUserId(), "video",
                    session.getStagingObjectName(), finalName, session.getDeclaredSize()), 10, TimeUnit.MINUTES);
            FileUploadVO result = uploadSessionService.complete(id);
            session.setStatus(COMPLETED); session.setFinalObjectName(result.getObjectName()); session.setDetectedDuration(result.getDetectedDuration());
            mapper.updateById(session);
            return result;
        }
    }

    public void cancel(String id) {
        UploadMultipartSession session = owned(id);
        if (UPLOADING.equals(session.getStatus())) minio.deleteObjects(partObjects(session));
        session.setStatus("ABORTED"); mapper.updateById(session);
    }

    @Scheduled(fixedDelayString = "${upload.multipart-cleanup-fixed-delay-milliseconds:3600000}")
    public void cleanupExpired() {
        List<UploadMultipartSession> expired = mapper.selectList(new LambdaQueryWrapper<UploadMultipartSession>()
                .in(UploadMultipartSession::getStatus, UPLOADING, "ABORTED").lt(UploadMultipartSession::getExpiresAt, LocalDateTime.now()).last("LIMIT 100"));
        for (UploadMultipartSession session : expired) {
            try { minio.deleteObjects(partObjects(session)); }
            catch (RuntimeException ignored) { continue; }
            if (UPLOADING.equals(session.getStatus())) session.setStatus("EXPIRED");
            mapper.updateById(session);
        }
    }

    private List<MultipartPartVO> parts(UploadMultipartSession session) {
        return java.util.stream.IntStream.rangeClosed(1, session.getTotalParts()).mapToObj(number -> {
            try {
                long expected = Math.min(session.getPartSize(), session.getDeclaredSize() - (long) (number - 1) * session.getPartSize());
                return minio.statObject(partObject(session, number)).size() == expected ? new MultipartPartVO(number, "present") : null;
            } catch (RuntimeException ignored) { return null; }
        }).filter(java.util.Objects::nonNull).toList();
    }
    private String partObject(UploadMultipartSession session, int partNumber) { return session.getStagingObjectName() + ".parts/" + String.format("%05d", partNumber); }
    private List<String> partObjects(UploadMultipartSession session) { return java.util.stream.IntStream.rangeClosed(1, session.getTotalParts()).mapToObj(number -> partObject(session, number)).toList(); }
    private MultipartUploadSessionVO view(UploadMultipartSession s, List<MultipartPartVO> parts) {
        return new MultipartUploadSessionVO(s.getId(), s.getStatus(), s.getPartSize(), s.getTotalParts(), s.getExpiresAt(), parts);
    }
    private UploadMultipartSession owned(String id) {
        UploadMultipartSession s = mapper.selectById(id);
        if (s == null || !s.getUserId().equals(SecurityUtils.getCurrentUser().userId())) throw new BusinessException(404, "上传会话不存在");
        return s;
    }
    private void ensureUploading(UploadMultipartSession s) { expireIfNeeded(s); if (!UPLOADING.equals(s.getStatus())) throw new BusinessException(409, "上传会话已不可继续使用"); }
    private void expireIfNeeded(UploadMultipartSession s) { if (UPLOADING.equals(s.getStatus()) && s.getExpiresAt().isBefore(LocalDateTime.now())) { s.setStatus("EXPIRED"); mapper.updateById(s); throw new BusinessException(410, "上传会话已过期"); } }
    private void validate(long size, String contentType) {
        if (size <= properties.getMultipartThresholdBytes() || size > properties.getMaxVideoSizeBytes()) throw new BusinessException(400, "该文件不在分片上传允许范围内");
        if (!"video/mp4".equalsIgnoreCase(contentType)) throw new BusinessException(400, "视频目前仅支持 MP4 格式");
    }
    private void ensureDiskHeadroom() {
        try { FileStore store = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir"))); if (store.getUsableSpace() < properties.getMinimumFreeDiskBytes()) throw new BusinessException(503, "服务器可用磁盘空间不足，暂不接受大文件上传"); }
        catch (java.io.IOException e) { throw new BusinessException(503, "无法确认服务器磁盘空间，暂不接受大文件上传"); }
    }
}
