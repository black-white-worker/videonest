package com.example.demo.module.upload.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
/**
 * 文件上传VO
 * VO：输出对象，controller返回给前端的数据
 * 文件上传成功之后返回给前端的信息
 */
@Data
@AllArgsConstructor
public class FileUploadVO {

    private String objectName;          // minio存储的对象文件名
    private Integer detectedDuration;   // 视频解析出来的时长，单位秒

    // 只传文件名的上传构造器
    public FileUploadVO(String objectName) {
        this.objectName = objectName;
    }
}
