package com.example.demo.module.upload.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.infrastructure.oss.service.MinioService;
import com.example.demo.module.upload.vo.FileUploadVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.example.demo.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/files")
@Slf4j
public class FileUploadController {

    private final MinioService minioService;

    public FileUploadController(MinioService minioService) {
        this.minioService = minioService;
    }

    @PostMapping("/cover")
    public ApiResponse<FileUploadVO> uploadCover(
            @RequestParam("file") MultipartFile file
    ) {
        String objectName = minioService.upload(file, "cover");
        log.info(
                "封面上传成功，userId={}，objectName={}，size={}",
                SecurityUtils.getCurrentUser().userId(),
                objectName,
                file.getSize()
        );
        return ApiResponse.success(new FileUploadVO(objectName));
    }

    @PostMapping("/video")
    public ApiResponse<FileUploadVO> uploadVideo(
            @RequestParam("file") MultipartFile file
    ) {
        String objectName = minioService.upload(file, "video");
        log.info(
                "视频上传成功，userId={}，objectName={}，size={}",
                SecurityUtils.getCurrentUser().userId(),
                objectName,
                file.getSize()
        );
        return ApiResponse.success(new FileUploadVO(objectName));
    }
}
