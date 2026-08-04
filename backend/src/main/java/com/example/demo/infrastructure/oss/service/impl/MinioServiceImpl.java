package com.example.demo.infrastructure.oss.service.impl;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.common.exception.StorageOperationException;
import com.example.demo.infrastructure.oss.config.MinioProperties;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.infrastructure.oss.service.StoredObjectMetadata;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储业务实现类
 * 实现 MinioService 接口，提供文件上传、下载、获取临时访问链接能力
 */

@Service
@Slf4j
public class MinioServiceImpl implements MinioService {

    //封面最大尺寸
    private static final long MAX_COVER_SIZE = 10 * 1024 * 1024L;

    //视频最大尺寸
    private static final long MAX_VIDEO_SIZE = 500 * 1024 * 1024L;

    // 允许上传的封面图片MIME类型集合
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png"
    );


    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;
    private final MinioProperties minioProperties;

    //构造器注入
    /**
     * 服务内部访问 MinIO：走内网地址，速度快、不走外网流量
     * */
    public MinioServiceImpl(
            MinioClient minioClient,
            MinioProperties minioProperties
    ) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        String publicEndpoint = StringUtils.hasText(
                minioProperties.getPublicEndpoint()
        )
                // 如果配置了公网地址，优先使用公网地址
                ? minioProperties.getPublicEndpoint()
                // 未配置公网地址，则降级使用默认内网endpoint
                : minioProperties.getEndpoint();

        // 构建【公网专用MinioClient】用于生成预签名URL
        // 预签名URL里面会嵌入endpoint地址！
        // 如果直接用内网client生成url，前端拿到地址无法公网访问
        this.publicMinioClient = MinioClient.builder()
                .endpoint(publicEndpoint)               // 使用公网节点地址
                .region(minioProperties.getRegion())    // 存储区域
                .credentials(                           // 账号密钥
                        minioProperties.getAccessKey(),
                        minioProperties.getSecretKey()
                )
                .build();
    }

    /**
     * 生成PUT方式预签名上传URL
     * 前端拿到URL直接PUT二进制文件上传到MinIO，无需后端中转文件流
     * @param objectName 文件在桶内唯一名称
     * @param expiryMinutes 链接有效时长（分钟）
     * @return 预签名上传地址
     */
    @Override
    public String createPresignedPutUrl(String objectName, int expiryMinutes) {
        try {
            ensureBucketExists();
            return publicMinioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .expiry(expiryMinutes, TimeUnit.MINUTES)    // 链接过期时间
                            .build()
            );
        } catch (IOException e) {
            throw storageFailure("PRESIGN_PUT", "连接 MinIO 生成上传地址失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("PRESIGN_PUT", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("PRESIGN_PUT", "MinIO 无法生成上传地址", isRetryable(e), e);
        }
    }

    /**
     * 查询文件元数据
     * @param objectName 对象名称
     * @return 文件大小、文件content-type封装对象
     */
    @Override
    public StoredObjectMetadata statObject(String objectName) {
        try {
            var stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build()
            );
            return new StoredObjectMetadata(stat.size(), stat.contentType());
        } catch (IOException e) {
            throw storageFailure("STAT", "连接 MinIO 获取资源信息失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("STAT", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("STAT", "MinIO 无法获取资源信息", isRetryable(e), e);
        }
    }

    @Override
    public void moveObject(String sourceObjectName, String targetObjectName) {
        try {
            minioClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(targetObjectName)
                            .source(
                                    CopySource.builder()
                                            .bucket(minioProperties.getBucketName())
                                            .object(sourceObjectName)
                                            .build()
                            )
                            .build()
            );
            deleteObject(sourceObjectName);
        } catch (IOException e) {
            throw storageFailure("PROMOTE", "连接 MinIO 确认上传对象失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("PROMOTE", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("PROMOTE", "MinIO 无法确认上传对象", isRetryable(e), e);
        }
    }

    /**
     * 接收前端表单文件上传至MinIO
     * @param file 前端上传文件对象 MultipartFile
     * @param folder 文件分类目录 cover=封面 video=视频
     * @return objectName MinIO桶内文件唯一路径（存入数据库，不存完整URL）
     */
    /**
     * 前端同时提交视频文件 + 视频封面图片
     * 后端分别两次调用 upload()
     * upload(videoFile,"video") → 上传视频
     * upload(coverFile,"cover") → 上传封面
     * */
    @Override
    public String upload(MultipartFile file, String folder) {
        //文件合法校验
        validateFile(file, folder);

        try {
            //检测存储桶是否存在
            ensureBucketExists();

            // 获取文件后缀名
            String extension = StringUtils.getFilenameExtension(
                    file.getOriginalFilename()
            );

            // 拼装文件路径规则：文件夹/日期/UUID/文件后缀
            String objectName = String.format(
                    "%s/%s/%s.%s",
                    folder,
                    LocalDate.now(),
                    UUID.randomUUID(),
                    extension
            );

            // MinIO SDK流式上传文件
            /**
             * 把文件输入流、文件大小、文件类型、目标桶名称、文件在 MinIO 里的路径信息，
             * 组装成 PutObjectArgs 上传参数对象，调用 MinIO SDK 方法，
             * 以流的方式把文件数据发送到 MinIO 服务端指定存储桶下的指定路径。*/
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())//存储桶
                            .object(objectName)//存储桶内路径
                            .stream(
                                    file.getInputStream(),//文件输入流
                                    file.getSize(),
                                    -1
                            )
                            .contentType(file.getContentType())
                            .build()
            );

            return objectName;
        } catch (IOException e) {
            throw storageFailure("UPLOAD", "读取上传文件或连接 MinIO 失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("UPLOAD", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("UPLOAD", "MinIO 拒绝上传请求", isRetryable(e), e);
        }
    }

    /**
     * 获取文件可访问地址
     * 逻辑：外部http链接直接放行；MinIO内部文件生成带时效的临时签名URL
     * @param objectNameOrUrl 文件唯一标识 / 外部网络地址
     * @return 可访问文件链接
     */
    @Override
    public String getAccessUrl(String objectNameOrUrl) {
        if (!StringUtils.hasText(objectNameOrUrl)) {
            return null;
        }

        // 兼容数据库中保存的外部视频、Unsplash 封面 URL
        if (objectNameOrUrl.startsWith("http://")
                || objectNameOrUrl.startsWith("https://")) {
            return objectNameOrUrl;
        }

        if (isPublicObject(objectNameOrUrl)) {
            return buildPublicObjectUrl(objectNameOrUrl);
        }

        try {
            // 生成1小时有效期临时访问URL
            return publicMinioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)// 请求方式 GET下载
                            .bucket(minioProperties.getBucketName())
                            .object(objectNameOrUrl)
                            .expiry(1, TimeUnit.HOURS)
                            .build()
            );
        } catch (IOException e) {
            throw storageFailure("PRESIGN", "连接 MinIO 生成访问地址失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("PRESIGN", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("PRESIGN", "MinIO 无法生成访问地址", isRetryable(e), e);
        }
    }

    private boolean isPublicObject(String objectName) {
        String prefixes = minioProperties.getPublicReadPrefixes();
        if (!StringUtils.hasText(prefixes)) {
            return false;
        }
        return Arrays.stream(prefixes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .anyMatch(objectName::startsWith);
    }

    private String buildPublicObjectUrl(String objectName) {
        String endpoint = StringUtils.hasText(minioProperties.getPublicEndpoint())
                ? minioProperties.getPublicEndpoint()
                : minioProperties.getEndpoint();
        String encodedObjectName = Arrays.stream(objectName.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8)
                        .replace("+", "%20"))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        return endpoint.replaceAll("/+$", "")
                + "/" + minioProperties.getBucketName()
                + "/" + encodedObjectName;
    }

    /**
     * 获取 MinIO 对象的真实字节数。
     */
    @Override
    public Long getObjectSize(String objectNameOrUrl) {
        if (!StringUtils.hasText(objectNameOrUrl)
                || objectNameOrUrl.startsWith("http://")
                || objectNameOrUrl.startsWith("https://")) {
            return null;
        }

        return statObject(objectNameOrUrl).size();
    }

    /**
     * 检测存储桶，不存在则自动创建。
     */
    private void ensureBucketExists()
            throws IOException, GeneralSecurityException, MinioException {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .build()
        );

        //如果不存在就创建
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .build()
            );
        }
    }

    /**
     * 文件校验工具方法
     * 根据传入的folder参数区分封面、视频两套校验规则
     * @param file 前端上传的文件对象
     * @param folder 文件分类标识 cover代表封面、video代表视频
     */
    private void validateFile(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }

        String contentType = file.getContentType();

        if (!StringUtils.hasText(contentType)) {
            throw new BusinessException(400, "无法识别文件类型");
        }

        //如果文件类型是封面图片
        if ("cover".equals(folder)) {
            if (!IMAGE_TYPES.contains(contentType)) {
                throw new BusinessException(
                        400,
                        "封面仅支持 JPG、PNG 格式"
                );
            }

            //限制大小
            if (file.getSize() > MAX_COVER_SIZE) {
                throw new BusinessException(400, "封面不能超过 10MB");
            }

            return;
        }

        //如果文件类型是视频
        if ("video".equals(folder)) {
            if (!"video/mp4".equals(contentType)) {
                throw new BusinessException(400, "视频目前仅支持 MP4 格式");
            }

            if (file.getSize() > MAX_VIDEO_SIZE) {
                throw new BusinessException(400, "视频不能超过 500MB");
            }

            return;
        }

        throw new BusinessException(400, "不支持的上传类型");
    }

    /**
     * 根据objectName从MinIO下载文件，返回输入流
     * @param objectName MinIO桶内文件唯一路径标识
     * @return InputStream 文件输入流，供调用方读取文件数据
     */
    @Override
    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(
                    io.minio.GetObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build()
            );
        } catch (IOException e) {
            throw storageFailure("DOWNLOAD", "连接 MinIO 下载资源失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("DOWNLOAD", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("DOWNLOAD", "MinIO 下载资源失败", isRetryable(e), e);
        }
    }

    /**
     * 将服务器本地磁盘上已存在的文件，上传到MinIO
     * 使用场景：FFmpeg视频转码完成后，本地生成临时mp4文件，调用此方法上传
     * @param localFile 服务器本地文件路径（磁盘上真实存在的文件）
     * @param objectName 文件存放在MinIO桶内的路径标识
     * @param contentType 文件媒体类型
     */
    /**
     * 用户前端上传原始视频 → 调用 upload()，直接传到 MinIO
     后端通过download()把原始视频下载到服务器本地临时目录
     FFmpeg 对本地视频转码，生成一份压缩后的视频（保存在服务器硬盘）
     调用 uploadFile()，把转码后的本地新视频上传 MinIO
     * */
    @Override
    public void uploadFile(
            java.nio.file.Path localFile,// 本地磁盘文件路径
            String objectName,// MinIO中保存的文件路径
            String contentType// 文件MIME类型
    ) {
        try {
            ensureBucketExists();

            minioClient.uploadObject(
                    io.minio.UploadObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .filename(localFile.toString())
                            .contentType(contentType)
                            .build()
            );
        } catch (IOException e) {
            throw storageFailure("UPLOAD_PROCESSED", "读取转码文件或连接 MinIO 失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("UPLOAD_PROCESSED", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("UPLOAD_PROCESSED", "MinIO 拒绝转码文件上传", isRetryable(e), e);
        }
    }

    /**
     * 删除MinIO文件，幂等实现
     */
    @Override
    public void deleteObject(String objectName) {
        // 参数为空 或者 是外部http链接，直接return，无需操作MinIO
        if (!StringUtils.hasText(objectName)
                || objectName.startsWith("http://")
                || objectName.startsWith("https://")) {
            return;
        }

        try {
            // 调用MinIO SDK执行删除
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build()
            );
            log.info("MinIO 资源删除成功，objectName={}", objectName);
        } catch (ErrorResponseException e) {
            //桶内找不到这个文件（文件本来就不存在）
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                // 直接return，不抛出异常,上层业务感知不到错误
                log.info("MinIO 资源已不存在，按幂等删除成功处理，objectName={}", objectName);
                return;
            }
            throw storageFailure("DELETE", "MinIO 拒绝删除资源", isRetryable(e), e);
        } catch (IOException e) {
            throw storageFailure("DELETE", "连接 MinIO 删除资源失败", true, e);
        } catch (GeneralSecurityException e) {
            throw storageFailure("DELETE", "MinIO 安全配置错误", false, e);
        } catch (MinioException e) {
            throw storageFailure("DELETE", "MinIO 删除资源失败", isRetryable(e), e);
        }
    }

    /**
     * 统一封装存储异常：打印错误日志，构建自定义异常向外抛出
     * @param operation 操作标识 UPLOAD/DOWNLOAD/PRESIGN/DELETE
     * @param message 错误描述
     * @param retryable 是否支持重试
     * @param cause 原始异常
     * @return 自定义存储异常
     */
    private StorageOperationException storageFailure(
            String operation,
            String message,
            boolean retryable,
            Throwable cause
    ) {
        log.error(
                "MinIO 操作失败，operation={}，retryable={}，message={}",
                operation,
                retryable,
                cause.getMessage(),
                cause
        );
        return new StorageOperationException(operation, message, retryable, cause);
    }

    /**
     * 判断MinIO异常是否可以重试
     * 408超时、429限流、5xx服务端错误 → 允许重试
     */
    private boolean isRetryable(MinioException exception) {
        if (exception instanceof ErrorResponseException errorResponseException) {
            int statusCode = errorResponseException.response().code();
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }
        return true;
    }
}
