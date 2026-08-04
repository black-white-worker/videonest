package com.example.demo.infrastructure.oss.service.impl;

import com.example.demo.infrastructure.oss.config.MinioProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinioServiceImplTest {

    @Test
    void processedMediaUsesStablePublicUrlWithoutSignature() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setPublicEndpoint("https://media.example.com/");
        properties.setRegion("us-east-1");
        properties.setAccessKey("videonest");
        properties.setSecretKey("videonest-secret");
        properties.setBucketName("videonest");
        properties.setPublicReadPrefixes("processed/,cover/processed/");

        MinioServiceImpl service = new MinioServiceImpl(null, properties);

        assertEquals(
                "https://media.example.com/videonest/processed/42/720p.mp4",
                service.getAccessUrl("processed/42/720p.mp4")
        );
        assertEquals(
                "https://media.example.com/videonest/cover/processed/42/%E5%B0%81%E9%9D%A2.jpg",
                service.getAccessUrl("cover/processed/42/封面.jpg")
        );
    }
}
