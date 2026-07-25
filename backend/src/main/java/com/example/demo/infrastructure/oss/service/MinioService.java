package com.example.demo.infrastructure.oss.service;

import org.springframework.web.multipart.MultipartFile;

public interface MinioService {

    String upload(MultipartFile file, String folder);

    String getAccessUrl(String objectNameOrUrl);
}