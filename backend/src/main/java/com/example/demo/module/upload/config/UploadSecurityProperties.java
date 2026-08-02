package com.example.demo.module.upload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "upload-security")
public class UploadSecurityProperties {
    private String antivirusCommand = "";
    private boolean antivirusRequired = false;
    private long scanTimeoutSeconds = 120;
    private long maxImagePixels = 50_000_000L;
}
