package com.example.demo.module.upload.vo;

import java.util.Map;

/**
 * 上传预签名VO
 * VO：输出对象，返回给前端，用于MinIO预签名上传
 * 存放预签名上传需要的全部信息：uploadId、上传地址、请求头、过期时间等
 */

public record UploadPresignVO(
        String uploadId,
        String objectName,
        String uploadUrl,
        String method,
        Map<String, String> headers,    // 需要前端携带的请求头，比如Content‑Type
        int expiresInSeconds            // 预签名有效期
) {
}
