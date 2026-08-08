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

/**
 * 死信消息后台管理控制器
 * 提供死信消息分页查询、手动重试、手动忽略接口，供管理后台调用
 */

@Validated
@RestController
@RequestMapping("/api/admin/dead-letters")
public class DeadLetterAdminController {

    private final DeadLetterRecordService recordService;

    public DeadLetterAdminController(DeadLetterRecordService recordService) {
        this.recordService = recordService;
    }

    /**
     * 分页查询死信消息列表接口 GET /api/admin/dead-letters
     * @param page 当前页码，默认值1，最小不能小于1
     * @param size 每页条数，默认10，最小1，最大50
     * @param status 死信状态过滤条件，可选，只允许PENDING/RETRIED/IGNORED三个状态值
     * @return ApiResponse包装分页对象PageResult<DeadLetterRecord>，返回分页死信记录数据
     */
    @GetMapping
    public ApiResponse<PageResult<DeadLetterRecord>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) long size,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|RETRIED|IGNORED")
            String status
    ) {
        // 调用service层分页查询逻辑，封装查询结果到ApiResponse.success，返回成功JSON响应
        return ApiResponse.success(recordService.list(page, size, status));
    }

    /**
     * 手动重试指定id的死信消息接口 POST /api/admin/dead-letters/{id}/retry
     * @param id 死信记录主键id，路径变量，必须大于等于1
     * @return ApiResponse<Void>，无返回业务数据，只返回成功状态码
     */
    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable @Min(1) Long id) {
        recordService.retry(id);
        return ApiResponse.success();
    }

    /**
     * 手动忽略死信消息接口 PUT /api/admin/dead-letters/{id}/ignore
     * 标记该死信不再重试，直接忽略处理
     * @param id 死信记录主键id，路径变量，必须大于等于1
     * @return ApiResponse<Void>，无业务返回数据
     */
    @PutMapping("/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable @Min(1) Long id) {
        recordService.ignore(id);
        return ApiResponse.success();
    }
}
