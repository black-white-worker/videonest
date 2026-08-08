package com.example.demo.module.upload.service;

/**
 * 上传票据 UploadTicket
 * service内部使用，不对外接口返回，不接收前端入参**
 * 保存一次预签名上传会话的全部业务票据信息，相当于一张上传任务单据
 * 只在后端Java内部流转，不会直接序列化传给前端
 */
public record UploadTicket(
        String uploadId,
        long userId,
        String type,
        // 临时staging（暂存区）对象名，文件先上传到暂存目录，校验通过再迁移；非法文件直接删除暂存文件
        String stagingObjectName,
        // 最终正式存储的对象名称，校验无误后文件最终存放的objectName
        String finalObjectName,
        long declaredSize
) {
}
