package com.example.demo.infrastructure.oss.service;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/**
 * MinIO 文件存储操作接口
 * 定义对象存储所有文件操作规范，面向业务提供统一文件操作能力
 */

public interface MinioService {

    String createPresignedPutUrl(
            String objectName,
            int expiryMinutes
    );

    StoredObjectMetadata statObject(String objectName);

    void moveObject(String sourceObjectName, String targetObjectName);

    /**
     * 接收前端上传文件（SpringMVC上传文件），上传至MinIO指定文件夹
     * @param file 前端传过来的文件对象 MultipartFile
     * @param folder 要存放的文件夹名称，例如 avatar、video
     * @return 返回文件访问地址 / 文件唯一标识 objectName
     */
    String upload(MultipartFile file, String folder);

    /**
     * 获取文件可直接访问的URL链接
     * @param objectNameOrUrl MinIO中的文件对象名称
     * @return 文件访问地址
     */
    String getAccessUrl(String objectNameOrUrl);

    /**
     * 获取 MinIO 对象的真实字节数。外部 URL 或空对象名返回 null。
     * @param objectNameOrUrl MinIO 中的对象名或外部 URL
     * @return 对象字节数；无法从 MinIO 统计时为 null
     */
    Long getObjectSize(String objectNameOrUrl);

    /**
     * 根据文件对象名从MinIO下载文件
     * @param objectName MinIO内文件唯一名称
     * @return 文件输入流InputStream，上层可以保存到本地、直接返回前端
     */
    InputStream download(String objectName);

    /**
     * 将服务器本地磁盘文件上传到MinIO
     * @param localFile 服务器本地文件路径
     * @param objectName 文件在MinIO桶内的名称
     * @param contentType 文件MIME类型（video/mp4、image/png等）
     */
    void uploadFile(Path localFile, String objectName, String contentType);

    /**
     * 根据对象名永久删除 MinIO 资源。对象不存在时按幂等成功处理。
     * @param objectName MinIO 桶内对象名
     */
    void deleteObject(String objectName);
}
