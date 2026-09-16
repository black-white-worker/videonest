package com.videonest.module.upload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Upload limits kept in one place so the browser and API follow the same policy. */
@Data
@Component
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {
    private long maxVideoSizeBytes = 800L * 1024 * 1024;
    private long multipartThresholdBytes = 400L * 1024 * 1024;
    private long multipartPartSizeBytes = 16L * 1024 * 1024;
    private int multipartConcurrency = 3;
    private int multipartSessionHours = 24;
    private int multipartUrlMinutes = 15;
    private int maxActiveMultipartSessionsPerUser = 1;
    private long minimumFreeDiskBytes = 10L * 1024 * 1024 * 1024;
}
