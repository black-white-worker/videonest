package com.example.demo.infrastructure.oss.service;

/**
 * Java Record 记录类：存储OSS上传文件后的元数据信息
 * @param size 文件大小（单位：字节 long类型）
 * @param contentType 文件MIME类型，例如 image/png、video/mp4、application/json
 */
public record StoredObjectMetadata(long size, String contentType) {
}
