package com.example.demo.module.upload.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.module.upload.vo.FileUploadVO;
import com.example.demo.module.upload.vo.UploadPresignVO;
import com.example.demo.module.upload.dto.UploadPresignRequest;
import com.example.demo.module.upload.service.UploadSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private final UploadSessionService uploadSessionService;

    public FileUploadController(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    @PostMapping("/presign")
    public ApiResponse<UploadPresignVO> presign(
            @Valid @RequestBody UploadPresignRequest request
    ) {
        return ApiResponse.success(uploadSessionService.issue(request));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ApiResponse<FileUploadVO> complete(@PathVariable String uploadId) {
        return ApiResponse.success(uploadSessionService.complete(uploadId));
    }

}
