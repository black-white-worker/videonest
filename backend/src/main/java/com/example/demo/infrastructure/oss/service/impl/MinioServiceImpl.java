package com.example.demo.infrastructure.oss.service.impl;

import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.oss.config.MinioProperties;
import com.example.demo.infrastructure.oss.service.MinioService;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MinioServiceImpl implements MinioService {

    private static final long MAX_COVER_SIZE = 10 * 1024 * 1024L;

    private static final long MAX_VIDEO_SIZE = 500 * 1024 * 1024L;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public MinioServiceImpl(
            MinioClient minioClient,
            MinioProperties minioProperties
    ) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    @Override
    public String upload(MultipartFile file, String folder) {
        validateFile(file, folder);

        try {
            ensureBucketExists();

            String extension = StringUtils.getFilenameExtension(
                    file.getOriginalFilename()
            );

            String objectName = String.format(
                    "%s/%s/%s.%s",
                    folder,
                    LocalDate.now(),
                    UUID.randomUUID(),
                    extension
            );

            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .stream(
                                    file.getInputStream(),
                                    file.getSize(),
                                    -1
                            )
                            .contentType(file.getContentType())
                            .build()
            );

            return objectName;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500, "文件上传失败：" + e.getMessage());
        }
    }

    @Override
    public String getAccessUrl(String objectNameOrUrl) {
        if (!StringUtils.hasText(objectNameOrUrl)) {
            return null;
        }

        // 兼容你之前数据库中保存的外部视频、Unsplash 封面 URL
        if (objectNameOrUrl.startsWith("http://")
                || objectNameOrUrl.startsWith("https://")) {
            return objectNameOrUrl;
        }

        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioProperties.getBucketName())
                            .object(objectNameOrUrl)
                            .expiry(1, TimeUnit.HOURS)
                            .build()
            );
        } catch (Exception e) {
            throw new BusinessException(500, "获取文件访问地址失败");
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .build()
        );

        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .build()
            );
        }
    }

    private void validateFile(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的文件");
        }

        String contentType = file.getContentType();

        if (!StringUtils.hasText(contentType)) {
            throw new BusinessException(400, "无法识别文件类型");
        }

        if ("cover".equals(folder)) {
            if (!IMAGE_TYPES.contains(contentType)) {
                throw new BusinessException(
                        400,
                        "封面仅支持 JPG、PNG、WebP 格式"
                );
            }

            if (file.getSize() > MAX_COVER_SIZE) {
                throw new BusinessException(400, "封面不能超过 5MB");
            }

            return;
        }

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

    @Override
    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(
                    io.minio.GetObjectArgs.builder()
                            .bucket(minioProperties.getBucketName())
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            throw new BusinessException(500, "下载 MinIO 视频失败：" + e.getMessage());
        }
    }

    @Override
    public void uploadFile(
            java.nio.file.Path localFile,
            String objectName,
            String contentType
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
        } catch (Exception e) {
            throw new BusinessException(500, "上传处理结果到 MinIO 失败：" + e.getMessage());
        }
    }

}
