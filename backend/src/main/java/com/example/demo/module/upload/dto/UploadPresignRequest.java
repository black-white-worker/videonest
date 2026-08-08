package com.example.demo.module.upload.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 预签名上传 请求DTO
 * DTO：输入对象，接收前端调用【获取预签名url】接口的JSON入参
 * 前端告诉后端：上传类型、原始文件名、文件MIME类型、文件大小
 * 后端根据这些信息生成MinIO预签名上传地址返回VO
 */
@Data
public class UploadPresignRequest {

    /**
     * 上传业务类型
     * cover：封面图片
     * video：视频文件
     * @ Pattern：正则限定只能传 cover 或者 video，其他值直接校验失败
     */
    @NotBlank
    @Pattern(regexp = "cover|video", message = "上传类型只支持 cover 或 video")
    private String type;

    /**
     * 用户原始文件名（例如 "summer.mp4"、"poster.jpg"）
     * 文件名最大长度255字符，防止超长文件名攻击
     */
    @NotBlank
    @Size(max = 255)
    private String fileName;

    /**
     * 文件MIME类型，例如 video/mp4  image/jpeg
     */
    @NotBlank
    @Size(max = 100)
    private String contentType;

    /**
     * 文件大小，单位字节 byte
     * 最大500MB
     */
    @Min(1)
    @Max(524288000)
    private long size;
}
