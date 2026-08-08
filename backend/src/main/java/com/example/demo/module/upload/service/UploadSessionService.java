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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 上传会话服务：预签名上传、上传完成校验、文件安全检测、临时文件转正
 */
@Service
@Slf4j
public class UploadSessionService {

    private static final long MAX_COVER_SIZE = 10 * 1024 * 1024L;
    private static final long MAX_VIDEO_SIZE = 500 * 1024 * 1024L;
    // 预签名URL有效期15分钟
    private static final int PRESIGN_MINUTES = 15;
    // complete完成接口分布式锁过期时间10分钟，防止死锁
    private static final int COMPLETE_LOCK_MINUTES = 10;

    /**
     * Redis Lua解锁脚本：防止锁误删除（A线程锁，B线程释放锁）
     * 逻辑：只有key存储的值等于传入的token，才执行DEL删除key；否则返回0不操作
     * KEYS[1]：锁key
     * ARGV[1]：锁的唯一token
     */

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT =
            // 锁存在，并且里面token等于当前线程的token，证明是自己的锁，才删除
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private final MinioService minioService;
    private final UploadedFileSecurityValidator securityValidator;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造器注入，Spring自动注入各个依赖Bean
     * @param minioService minio操作服务
     * @param securityValidator 文件安全校验组件
     * @param redisTemplate 对象redis模板
     * @param stringRedisTemplate 字符串redis模板
     */
    public UploadSessionService(
            MinioService minioService,
            UploadedFileSecurityValidator securityValidator,
            RedisTemplate<String, Object> redisTemplate,
            StringRedisTemplate stringRedisTemplate
    ) {
        this.minioService = minioService;
        this.securityValidator = securityValidator;
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成预签名上传凭证，给前端返回PUT预签名URL，前端直接上传文件到MinIO临时目录
     * @param request 前端请求：文件类型cover/video、contentType、声明size
     * @return UploadPresignVO 返回uploadId、预签名url、http方法、请求头、过期秒数
     */
    public UploadPresignVO issue(UploadPresignRequest request) {
        long userId = SecurityUtils.getCurrentUser().userId();
        // 校验前端传入元数据（大小、文件类型是否支持）
        validateDeclaredMetadata(request);

        String uploadId = UUID.randomUUID().toString();
        // 根据type+contentType解析出文件后缀 jpg/png/mp4
        String extension = canonicalExtension(request.getType(), request.getContentType());
        String objectId = UUID.randomUUID().toString();
        // 临时staging目录路径：staging/用户id/日期/唯一id.后缀，文件先上传到此临时目录
        String stagingObjectName = "staging/%d/%s/%s.%s".formatted(
                userId, LocalDate.now(), objectId, extension
        );
        // 正式目录路径：cover或者video + 用户id + 日期 + 文件，校验通过后move到这个路径
        String finalObjectName = "%s/%d/%s/%s.%s".formatted(
                request.getType(), userId, LocalDate.now(), objectId, extension
        );
        UploadTicket ticket = new UploadTicket(
                uploadId, userId, request.getType(), stagingObjectName,
                finalObjectName, request.getSize()
        );
        // Redis存储上传票据，过期时间：预签名15分钟+5分钟缓冲，防止url刚过期票据立刻消失
        redisTemplate.opsForValue().set(
                RedisKeys.uploadTicket(uploadId),
                ticket,
                PRESIGN_MINUTES + 5L,
                TimeUnit.MINUTES
        );

        // 返回给前端VO：uploadId、临时对象名、预签名PUT url、请求方法、请求头、过期秒数
        return new UploadPresignVO(
                uploadId,
                stagingObjectName,
                minioService.createPresignedPutUrl(stagingObjectName, PRESIGN_MINUTES),
                "PUT",
                Map.of("Content-Type", request.getContentType()),
                PRESIGN_MINUTES * 60
        );
    }

    /**
     * 上传完成接口：前端文件上传MinIO成功后调用complete，执行校验、安全扫描、移动文件
     * 使用Redis分布式锁，防止同一个uploadId并发重复提交
     * @param uploadId 上传会话id
     * @return FileUploadVO 返回正式对象名、视频时长
     */
    public FileUploadVO complete(String uploadId) {
        String lockKey = RedisKeys.uploadCompleteLock(uploadId);
        // 锁的唯一token，用于lua脚本安全释放锁，防止释放别人的锁
        String lockToken = UUID.randomUUID().toString();

        /*
        * opsForValue() 返回 ValueOperations 对象，它就是操作 Redis 远端字符串数据的手柄；
        * 传入 key、value，它内部组装命令、通过网络发给 Redis，修改 Redis 服务端上保存的数据。
        * */

        //setIfAbsent()对应 Redis 的 NX 选项：Only set if key does not exist，key 不存在才设置
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                lockToken,
                COMPLETE_LOCK_MINUTES,
                TimeUnit.MINUTES
        );
        // 不直接写if(!locked)，是为了规避Boolean包装类 null 空指针
        //  防止重复提交，和重复执行
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(409, "该上传正在校验，请勿重复提交");
        }
        try {
            return completeLocked(uploadId);
        } finally {
            // 无论业务成功失败，都执行释放锁
            unlockComplete(lockKey, lockToken);
        }
    }

    /**
     * 获取锁成功后的上传完成核心逻辑
     * @param uploadId 上传会话id
     * @return FileUploadVO
     */
    private FileUploadVO completeLocked(String uploadId) {
        // 获取当前登录用户id，校验票据归属
        long userId = SecurityUtils.getCurrentUser().userId();
        // 从Redis取出上传票据
        Object value = redisTemplate.opsForValue().get(RedisKeys.uploadTicket(uploadId));
        // 判断票据是否存在，并且票据所属用户与当前登录用户一致，防止越权操作别人上传凭证
        if (!(value instanceof UploadTicket ticket) || ticket.userId() != userId) {
            throw new BusinessException(404, "上传凭证不存在、已过期或不属于当前用户");
        }

        try {
            StoredObjectMetadata metadata = minioService.statObject(
                    ticket.stagingObjectName()
            );
            // 判断文件大小合法性：不能<=0，并且必须和前端声明size相等，防止篡改
            if (metadata.size() <= 0 || metadata.size() != ticket.declaredSize()) {
                throw new BusinessException(400, "对象实际大小与上传凭证不一致");
            }
            // 根据文件类型取最大允许大小
            long maxSize = "cover".equals(ticket.type())
                    ? MAX_COVER_SIZE : MAX_VIDEO_SIZE;
            if (metadata.size() > maxSize) {
                throw new BusinessException(413, "上传对象超过大小限制");
            }

            // 文件安全检测：图片校验、视频解码校验、病毒/格式校验等
            UploadedFileSecurityValidator.Inspection inspection =
                    securityValidator.inspect(ticket.stagingObjectName(), ticket.type());

            // 在Redis写入确认标记，标记该文件已经校验完成，有效期2小时
            registerConfirmed(ticket.finalObjectName(), userId, ticket.type());
            minioService.moveObject(
                    ticket.stagingObjectName(), ticket.finalObjectName()
            );
            // 删除Redis上传票据，票据一次性使用
            redisTemplate.delete(RedisKeys.uploadTicket(uploadId));
            // 返回结果VO：正式存储路径、视频解析出来的时长
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

    /**
     * 使用Lua脚本安全释放分布式锁
     * @param lockKey redis锁key
     * @param lockToken 锁的唯一token
     */
    private void unlockComplete(String lockKey, String lockToken) {
        try {
            // 执行lua脚本释放锁，防止释放其他线程的锁
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT,
                    java.util.List.of(lockKey),
                    lockToken
            );
        } catch (RuntimeException e) {
            // 锁会在十分钟后自动过期，释放失败不覆盖原始上传结果。
            log.warn("释放上传完成锁失败，lockKey={}", lockKey, e);
        }
    }

    /**
     * 注册已校验完成标记：正式文件objectName，记录所属用户与文件类型，有效期2小时
     * 后续业务保存数据库前会调用assertConfirmed校验这个标记，防止直接传入非法minio路径绕过上传流程
     * @param objectName minio正式文件路径
     * @param userId 用户id
     * @param type cover / video
     */
    /*往 Redis 写入一条 “该文件已经安全校验完成” 的标记，有效期 2 小时。无返回值，只做写入操作*/
    public void registerConfirmed(String objectName, long userId, String type) {
        redisTemplate.opsForValue().set(
                RedisKeys.confirmedUpload(objectName),
                userId + ":" + type,
                2,
                TimeUnit.HOURS
        );
    }

    /**
     * 断言校验文件是否已经完成安全校验，归属用户匹配
     * 业务层保存视频/封面数据库前调用，防止传入未经过complete流程的minio路径
     * @param objectName minio正式文件路径
     * @param userId 当前操作用户id
     * @param type cover/video
     */
    /*主要就是读取 Redis 里面那个确认标记，并且做校验断言。*/
    public void assertConfirmed(String objectName, long userId, String type) {
        Object marker = redisTemplate.opsForValue().get(
                RedisKeys.confirmedUpload(objectName)
        );
        if (!(userId + ":" + type).equals(marker)) {
            throw new BusinessException(400, "上传对象未完成校验、已过期或不属于当前用户");
        }
    }

    /**
     * 文件被业务消费（存入数据库），清除redis确认标记，防止重复使用该文件
     * @param objectName minio正式对象路径
     */
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

    /**
     * 预签名生成阶段校验前端声明的元数据：文件大小、contentType合法性
     * @param request 预签名请求dto
     */
    private void validateDeclaredMetadata(UploadPresignRequest request) {
        long max = "cover".equals(request.getType()) ? MAX_COVER_SIZE : MAX_VIDEO_SIZE;
        if (request.getSize() > max) {
            throw new BusinessException(413, "文件超过允许大小");
        }
        canonicalExtension(request.getType(), request.getContentType());
    }

    /**
     * 根据文件type + contentType，映射为标准文件后缀名；不支持类型抛出业务异常
     * @param type cover / video
     * @param contentType http媒体类型，例如 image/jpeg
     * @return jpg/png/mp4
     */
    private String canonicalExtension(String type, String contentType) {
        return switch (type + ":" + contentType.toLowerCase()) {
            case "cover:image/jpeg" -> "jpg";
            case "cover:image/png" -> "png";
            case "video:video/mp4" -> "mp4";
            default -> throw new BusinessException(400, "声明的媒体类型不受支持");
        };
    }

    /**
     * 判断发生异常时，是否需要删除临时staging对象
     * 规则：非可重试存储异常、业务异常(4xx) → 删除文件；5xx服务内部异常不删除，允许重试complete
     * @param error completeLocked抛出的运行时异常
     * @return true 需要删除临时文件；false 保留票据允许重试
     */
    private boolean shouldDiscardObject(RuntimeException error) {
        // 如果是存储操作异常，判断是否不可重试，不可重试则丢弃文件
        if (error instanceof StorageOperationException storageError) {
            return !storageError.isRetryable();
        }
        // 如果是业务异常，code<500代表客户端错误（400/413等），丢弃文件
        if (error instanceof BusinessException businessError) {
            return businessError.getCode() < 500;
        }
        // 其他异常（5xx、未知异常）返回false，保留临时文件，票据还在可以重试complete
        return false;
    }

}
