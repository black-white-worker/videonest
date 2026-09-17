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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * 大文件分片上传服务。
 * 负责分片上传会话的完整生命周期管理：
 *
 * {@link #create} 创建分片上传会话（校验元数据、磁盘余量、并发会话数）
 * {@link #presignPart} 为单个分片签发预签名上传地址
 * {@link #complete} 合并所有分片为完整对象并触发正式入库流程
 * {@link #cancel} 取消会话并清理已上传分片
 * {@link #cleanupExpired} 定时清理过期未完成的会话
 *
 *
 * 会话状态流转：UPLOADING（上传中）→ COMPLETED（已完成）/ ABORTED（已取消）/ EXPIRED（已过期）。</p>
 */
@Service
public class MultipartUploadService {

    /** 会话状态：上传中 */
    private static final String UPLOADING = "UPLOADING";
    /** 会话状态：已合并完成 */
    private static final String COMPLETED = "COMPLETED";

    private final UploadMultipartSessionMapper mapper;
    private final MinioService minio;
    private final UploadProperties properties;
    private final RedisTemplate<String, Object> redis;
    private final UploadSessionService uploadSessionService;
    private final RenewableRedisLock locks;

    /**
     * 构造器注入依赖：分片会话表 Mapper、MinIO 操作服务、上传配置、Redis 模板、
     * 直传会话服务（复用其完整/校验逻辑）、可续期分布式锁。
     */
    public MultipartUploadService(UploadMultipartSessionMapper mapper, MinioService minio, UploadProperties properties,
                                  RedisTemplate<String, Object> redis, UploadSessionService uploadSessionService,
                                  RenewableRedisLock locks) {
        this.mapper = mapper;
        this.minio = minio;
        this.properties = properties;
        this.redis = redis;
        this.uploadSessionService = uploadSessionService;
        this.locks = locks;
    }

    /**
     * 创建分片上传会话：校验元数据与磁盘余量、检查用户在途会话数，
     * 生成会话记录并落库，返回会话摘要（初始无已上传分片）。
     *
     * @param request 创建分片上传会话的请求 DTO（文件名、类型、声明大小、指纹等）
     * @return 新创建的分片上传会话摘要
     */
    public MultipartUploadSessionVO create(MultipartUploadCreateRequest request) {
        long userId = SecurityUtils.getCurrentUser().userId();
        // 1. 校验文件大小与类型是否处于分片上传允许范围
        validate(request.getSize(), request.getContentType());
        // 2. 校验服务器临时磁盘可用空间，避免大文件写满磁盘
        ensureDiskHeadroom();

        // 3. 统计当前用户处于上传中且未过期的会话数，限制并发大文件上传数量
        long active = mapper.selectCount(new LambdaQueryWrapper<UploadMultipartSession>()
                .eq(UploadMultipartSession::getUserId, userId)
                .eq(UploadMultipartSession::getStatus, UPLOADING)
                .gt(UploadMultipartSession::getExpiresAt, LocalDateTime.now()));
        if (active >= properties.getMaxActiveMultipartSessionsPerUser()) {
            throw new BusinessException(429, "已有大文件正在上传，请完成或取消后再试");
        }

        // 4. 生成会话 ID 与分片暂存对象名（staging/用户id/日期/随机id.mp4）
        String id = UUID.randomUUID().toString();
        String objectId = UUID.randomUUID().toString();
        String staging = "staging/%d/%s/%s.mp4".formatted(userId, LocalDate.now(), objectId);

        // 5. 按固定分片大小向上取整计算总分片数
        long partSize = properties.getMultipartPartSizeBytes();
        int totalParts = Math.toIntExact((request.getSize() + partSize - 1) / partSize);

        // 6. 组装会话实体并入库，初始状态为 UPLOADING，带过期时间
        UploadMultipartSession session = new UploadMultipartSession();
        session.setId(id);
        session.setUserId(userId);
        session.setType("video");
        session.setFileName(request.getFileName());
        session.setContentType(request.getContentType());
        session.setDeclaredSize(request.getSize());
        session.setFingerprint(request.getFingerprint());
        session.setPartSize(partSize);
        session.setTotalParts(totalParts);
        session.setStagingObjectName(staging);
        session.setMinioUploadId(id);
        session.setStatus(UPLOADING);
        session.setExpiresAt(LocalDateTime.now().plusHours(properties.getMultipartSessionHours()));
        mapper.insert(session);

        return view(session, List.of());
    }

    /**
     * 查询分片上传会话当前状态；若会话已过期会在此处触发过期处理。
     *
     * @param id 会话 ID
     * @return 会话摘要，含已上传分片列表（仅在 UPLOADING 状态下返回分片明细）
     */
    public MultipartUploadSessionVO status(String id) {
        UploadMultipartSession session = owned(id);
        expireIfNeeded(session);
        return view(session, UPLOADING.equals(session.getStatus()) ? parts(session) : List.of());
    }

    /**
     * 为指定分片签发预签名上传地址，前端通过该地址将分片直传 MinIO。
     *
     * @param id         会话 ID
     * @param partNumber 分片编号（从 1 开始）
     * @return 分片预签名上传凭证（编号、URL、请求头、有效期秒数）
     */
    public MultipartPartPresignVO presignPart(String id, int partNumber) {
        UploadMultipartSession session = owned(id);
        ensureUploading(session);
        if (partNumber < 1 || partNumber > session.getTotalParts()) {
            throw new BusinessException(400, "分片编号不合法");
        }
        return new MultipartPartPresignVO(partNumber,
                minio.createPresignedPartUploadUrl(partObject(session, partNumber), partNumber,
                        properties.getMultipartUrlMinutes()),
                Map.of("Content-Type", session.getContentType()), properties.getMultipartUrlMinutes() * 60);
    }

    /**
     * 完成分片上传：校验分片齐备后合并为完整对象，删除分片，
     * 写入上传票据并复用直传链路的 complete 完成正式入库。
     * 使用可续期 Redis 锁防止同一会话并发重复提交。
     *
     * @param id      会话 ID
     * @param request 完成请求，携带前端确认的全部已上传分片
     * @return 正式对象名与视频时长
     */
    public FileUploadVO complete(String id, MultipartUploadCompleteRequest request) {
        UploadMultipartSession session = owned(id);
        // 会话已完成的场景直接返回结果，保证幂等
        if (COMPLETED.equals(session.getStatus())) {
            return new FileUploadVO(session.getFinalObjectName(), session.getDetectedDuration());
        }
        ensureUploading(session);

        // 获取分布式锁：同一会话只允许一个 complete 请求执行合并流程
        var lock = locks.tryAcquire("videonest:lock:multipart-complete:" + id, 10, TimeUnit.MINUTES);
        if (lock.isEmpty()) {
            throw new BusinessException(409, "该上传正在完成，请勿重复提交");
        }
        try (var ignored = lock.get()) {
            // 加锁成功后重新读取会话，防止锁等待期间会话状态已变化
            session = owned(id);
            if (COMPLETED.equals(session.getStatus())) {
                return new FileUploadVO(session.getFinalObjectName(), session.getDetectedDuration());
            }
            // 前端声明分片数与服务端实际探测到的分片数都必须等于总分片数
            if (request.getParts().size() != session.getTotalParts() || parts(session).size() != session.getTotalParts()) {
                throw new BusinessException(400, "分片尚未全部完成或上传结果不一致");
            }

            // 将全部分片合并为暂存对象，随后清理分片文件
            minio.composeObjects(session.getStagingObjectName(), partObjects(session));
            minio.deleteObjects(partObjects(session));

            // 生成正式对象名，写入上传票据（直传链路的 complete 依赖该票据完成校验与入库）
            String finalName = "video/%d/%s/%s.mp4".formatted(session.getUserId(), LocalDate.now(), UUID.randomUUID());
            redis.opsForValue().set(RedisKeys.uploadTicket(id), new UploadTicket(id, session.getUserId(), "video",
                    session.getStagingObjectName(), finalName, session.getDeclaredSize()), 10, TimeUnit.MINUTES);

            // 复用直传上传完成逻辑：校验对象、安全检测、移动到正式路径并返回时长
            FileUploadVO result = uploadSessionService.complete(id);
            // 更新会话状态为已完成并记录正式对象名与时长
            session.setStatus(COMPLETED);
            session.setFinalObjectName(result.getObjectName());
            session.setDetectedDuration(result.getDetectedDuration());
            mapper.updateById(session);
            return result;
        }
    }

    /**
     * 取消分片上传会话：上传中的会话先删除已上传分片，再将会话状态置为 ABORTED。
     *
     * @param id 会话 ID
     */
    public void cancel(String id) {
        UploadMultipartSession session = owned(id);
        if (UPLOADING.equals(session.getStatus())) {
            minio.deleteObjects(partObjects(session));
        }
        session.setStatus("ABORTED");
        mapper.updateById(session);
    }

    /**
     * 定时清理过期未完成的分片上传会话（默认每小时执行一次，可通过
     * upload.multipart-cleanup-fixed-delay-milliseconds 配置调整）。
     * 每批最多处理 100 条：删除分片文件后，UPLOADING 会话标记为 EXPIRED；
     * 若删除分片失败则跳过该会话，等待下一轮重试。
     */
    @Scheduled(fixedDelayString = "${upload.multipart-cleanup-fixed-delay-milliseconds:3600000}")
    public void cleanupExpired() {
        List<UploadMultipartSession> expired = mapper.selectList(new LambdaQueryWrapper<UploadMultipartSession>()
                .in(UploadMultipartSession::getStatus, UPLOADING, "ABORTED")
                .lt(UploadMultipartSession::getExpiresAt, LocalDateTime.now())
                .last("LIMIT 100"));
        for (UploadMultipartSession session : expired) {
            try {
                // 先删除对象存储中的分片文件，失败则跳过本次清理（保留原状态）
                minio.deleteObjects(partObjects(session));
            } catch (RuntimeException ignored) {
                continue;
            }
            if (UPLOADING.equals(session.getStatus())) {
                session.setStatus("EXPIRED");
            }
            mapper.updateById(session);
        }
    }

    /**
     * 探测当前会话已成功上传的分片：逐个校验对象实际大小是否与理论分片大小一致。
     *
     * @param session 分片上传会话
     * @return 已上传且大小正确的分片列表（状态标记为 present）
     */
    private List<MultipartPartVO> parts(UploadMultipartSession session) {
        return IntStream.rangeClosed(1, session.getTotalParts()).mapToObj(number -> {
            try {
                // 理论分片大小：非最后一片为固定分片大小，最后一片为剩余字节数
                long expected = Math.min(session.getPartSize(),
                        session.getDeclaredSize() - (long) (number - 1) * session.getPartSize());
                return minio.statObject(partObject(session, number)).size() == expected
                        ? new MultipartPartVO(number, "present") : null;
            } catch (RuntimeException ignored) {
                // 分片不存在或探测失败视为未上传
                return null;
            }
        }).filter(Objects::nonNull).toList();
    }

    /**
     * 根据会话与分片编号拼接分片在 MinIO 中的对象名：{暂存对象名}.parts/{5位分片编号}。
     */
    private String partObject(UploadMultipartSession session, int partNumber) {
        return session.getStagingObjectName() + ".parts/" + String.format("%05d", partNumber);
    }

    /**
     * 拼接当前会话全部分片的对象名列表（编号 1 到 totalParts）。
     */
    private List<String> partObjects(UploadMultipartSession session) {
        return IntStream.rangeClosed(1, session.getTotalParts())
                .mapToObj(number -> partObject(session, number))
                .toList();
    }

    /**
     * 将会话实体组装为对外返回的 VO（含已上传分片列表）。
     */
    private MultipartUploadSessionVO view(UploadMultipartSession s, List<MultipartPartVO> parts) {
        return new MultipartUploadSessionVO(s.getId(), s.getStatus(), s.getPartSize(),
                s.getTotalParts(), s.getExpiresAt(), parts);
    }

    /**
     * 按 ID 查询会话并校验归属：会话不存在或不属于当前登录用户时抛出 404。
     *
     * @return 归属当前用户的会话实体
     */
    private UploadMultipartSession owned(String id) {
        UploadMultipartSession s = mapper.selectById(id);
        if (s == null || !s.getUserId().equals(SecurityUtils.getCurrentUser().userId())) {
            throw new BusinessException(404, "上传会话不存在");
        }
        return s;
    }

    /**
     * 断言会话仍处于可继续使用状态（未过期且为 UPLOADING），否则抛出 409。
     */
    private void ensureUploading(UploadMultipartSession s) {
        expireIfNeeded(s);
        if (!UPLOADING.equals(s.getStatus())) {
            throw new BusinessException(409, "上传会话已不可继续使用");
        }
    }

    /**
     * 若会话为 UPLOADING 且已超过过期时间，则标记为 EXPIRED 并抛出 410。
     */
    private void expireIfNeeded(UploadMultipartSession s) {
        if (UPLOADING.equals(s.getStatus()) && s.getExpiresAt().isBefore(LocalDateTime.now())) {
            s.setStatus("EXPIRED");
            mapper.updateById(s);
            throw new BusinessException(410, "上传会话已过期");
        }
    }

    /**
     * 校验分片上传的元数据：大小须超过单文件上传阈值且不超过视频上限，类型仅支持 video/mp4。
     */
    private void validate(long size, String contentType) {
        if (size <= properties.getMultipartThresholdBytes() || size > properties.getMaxVideoSizeBytes()) {
            throw new BusinessException(400, "该文件不在分片上传允许范围内");
        }
        if (!"video/mp4".equalsIgnoreCase(contentType)) {
            throw new BusinessException(400, "视频目前仅支持 MP4 格式");
        }
    }

    /**
     * 检查服务器临时目录所在磁盘的可用空间是否充足，不足时拒绝创建上传会话。
     */
    private void ensureDiskHeadroom() {
        try {
            FileStore store = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")));
            if (store.getUsableSpace() < properties.getMinimumFreeDiskBytes()) {
                throw new BusinessException(503, "服务器可用磁盘空间不足，暂不接受大文件上传");
            }
        } catch (java.io.IOException e) {
            throw new BusinessException(503, "无法确认服务器磁盘空间，暂不接受大文件上传");
        }
    }
}
