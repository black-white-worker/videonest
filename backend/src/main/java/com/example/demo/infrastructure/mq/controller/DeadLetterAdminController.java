package com.example.demo.infrastructure.mq.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;
import com.example.demo.infrastructure.mq.service.DeadLetterRecordService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/dead-letters")
public class DeadLetterAdminController {

    private final DeadLetterRecordService recordService;

    public DeadLetterAdminController(DeadLetterRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<PageResult<DeadLetterRecord>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) long size,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|RETRIED|IGNORED")
            String status
    ) {
        return ApiResponse.success(recordService.list(page, size, status));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable @Min(1) Long id) {
        recordService.retry(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable @Min(1) Long id) {
        recordService.ignore(id);
        return ApiResponse.success();
    }
}
