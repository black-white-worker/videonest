package com.videonest.module.interaction.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.interaction.service.AdminCommentService;
import com.videonest.module.interaction.vo.AdminCommentVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 管理员后台评论管理控制器
 * 提供后台对评论的分页查询、删除、恢复已删除评论三个接口能力
 */
@RestController
@Validated
@RequestMapping("/api/admin/comments")
public class AdminCommentController {
    private final AdminCommentService adminCommentService;

    public AdminCommentController(AdminCommentService adminCommentService) {
        this.adminCommentService = adminCommentService;
    }

    /**
     * 后台分页条件查询评论列表接口
     * 请求方式：GET
     * 请求地址：/api/admin/comments
     * @param page 当前页码，默认值1，最小不能小于1
     * @param size 每页条数，默认20，最小1最大100，防止超大分页压垮数据库
     * @param videoId 可选筛选条件：所属视频ID
     * @param keyword 可选筛选条件：评论内容模糊搜索关键词
     * @param status 可选筛选条件：评论状态 0/1，限制0到1之间
     * @return 统一包装分页评论VO数据
     */
    /**
     * 前端传入 5 条查询筛选参数经 Controller 校验后逐层传递至 Mapper 执行多字段联查，
     * 查询结果封装为 IPage<AdminCommentVO>，
     * 先后经过 Service 分页转换成 PageResult.of(pageData);
     * 与 Controller 转换成全局响应包装ApiResponse.success后返回前端
     * */
    @GetMapping
    public ApiResponse<PageResult<AdminCommentVO>> list(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) @Min(1) Long videoId,
            // keyword非必传，模糊查询评论关键字，无数值校验
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status
    ) {
        return ApiResponse.success(
                adminCommentService.listComments(page, size, videoId, keyword, status));
    }

    /**
     * 管理员逻辑删除单条评论接口
     * 请求方式：DELETE
     * 地址：/api/admin/comments/{commentId}
     * @param commentId 路径传递的评论主键ID，必须大于等于1
     * @return 无返回数据，仅返回成功状态
     */
    @DeleteMapping("/{commentId}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) Long commentId) {
        adminCommentService.deleteComment(commentId);
        return ApiResponse.success();
    }

    /**
     * 管理员恢复已逻辑删除的评论接口
     * 请求方式：PUT 更新操作
     * 地址：/api/admin/comments/{commentId}/restore
     * @param commentId 评论主键ID
     * @return 空数据成功响应
     */
    @PutMapping("/{commentId}/restore")
    public ApiResponse<Void> restore(@PathVariable @Min(1) Long commentId) {
        adminCommentService.restoreComment(commentId);
        return ApiResponse.success();
    }
}
