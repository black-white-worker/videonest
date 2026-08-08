package com.example.demo.module.upload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件上传安全相关配置属性类
 * 读取配置文件中前缀为 upload‑security 的全部配置，映射到下面成员变量
 */
@Data
@Component
@ConfigurationProperties(prefix = "upload-security")
public class UploadSecurityProperties {
    // 病毒扫描命令
    private String antivirusCommand = "";
    // 关闭强制病毒扫描
    private boolean antivirusRequired = false;
    // 病毒扫描超市时间
    private long scanTimeoutSeconds = 120;
    // 图片最大像素
    private long maxImagePixels = 50_000_000L;
}
