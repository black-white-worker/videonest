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

/**
 * 预签名流程
 * 用户在浏览器选择本地文件后，前端提取文件的 type、fileName、size、contentType 等元信息组装成 JSON，
 * 通过 POST 请求调用 /upload/presign 接口将该 JSON 参数传递给 Controller；
 * Controller 使用@Valid @RequestBody UploadPresignRequest dto接收数据，
 * 借助 UploadPresignRequest DTO 上的@NotBlank、@Pattern、@Min等校验注解完成参数合法性校验，
 * 此阶段只传输描述文件的文本参数，并不会传输真实的文件二进制，
 * 接着 Controller 调用 UploadSessionService，Service 调用 MinIO SDK 生成临时预签名 URL，
 * 再将 uploadId、uploadUrl、method、headers、expiresInSeconds 等信息封装到输出 VO 对象 UploadPresignVO 中
 * ，Controller 通过return ApiResponse.success(vo)，由 SpringMVC 的 Jackson 将 VO 序列化为 JSON 返回给前端；
 * 前端拿到返回的 VO JSON 后，读取 uploadUrl、method、headers，
 * 由浏览器直接发起 PUT 请求，将本地文件二进制上传至 MinIO，整个文件上传过程绕过 Java 后端服务。
 * */
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
