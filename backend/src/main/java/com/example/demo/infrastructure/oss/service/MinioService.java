package com.example.demo.infrastructure.oss.service;

import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

public interface MinioService {

    String upload(MultipartFile file, String folder);

    String getAccessUrl(String objectNameOrUrl);

    InputStream download(String objectName);

    void uploadFile(Path localFile, String objectName, String contentType);
}
