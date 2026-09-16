package com.videonest.module.video.controller;

import com.videonest.common.api.ApiResponse;
import com.videonest.common.api.PageResult;
import com.videonest.module.video.service.VideoService;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.VideoViewReportVO;
import com.videonest.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Video 接口控制器。
 */
@RestController
@Validated
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

    /** 分页查询已发布视频，支持按分区和标题关键词筛选。 */
    @GetMapping
    public ApiResponse<PageResult<VideoListItemVO>> list(
            @RequestParam(required = false) Long categoryId,

            @RequestParam(required = false)
            @Size(max = 100, message = "搜索关键词不能超过 100 个字符") String keyword,

            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码不能小于 1")
            long page,

            @RequestParam(defaultValue = "12")
            @Min(value = 1, message = "每页数量不能小于 1")
            @Max(value = 50, message = "每页数量不能超过 50")
            long size
    ) {
        return ApiResponse.success(
                videoService.listPublishedVideos(categoryId, keyword, page, size)
        );
    }


    /** 获取单个已发布视频的详情；服务层负责生成可访问的媒体地址。 */
    @GetMapping("/{id}")
    public ApiResponse<VideoDetailVO> detail(
            @PathVariable
            @Min(value = 1, message = "视频 ID 必须大于 0")
            Long id
    ) {
        return ApiResponse.success(
                videoService.getPublishedVideoDetail(id)
        );
    }

    /** 获取基于热度计算的热门视频列表。 */
    @GetMapping("/hot")
    public ApiResponse<List<VideoListItemVO>> hot(
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "热榜数量必须大于 0")
            @Max(value = 50, message = "热榜数量不能超过 50")
            int limit
    ) {
        return ApiResponse.success(videoService.listHotVideos(limit));
    }

    /**
     * 上报一次视频播放事件。
     * 已登录用户以用户 ID 去重，匿名用户以 IP 哈希去重；服务层负责限流、去重窗口和播放量累计。
     */
    @PostMapping("/{id}/views")
    public ApiResponse<VideoViewReportVO> reportView(
            @PathVariable @Min(1) Long id,
            HttpServletRequest request
    ) {
        // 从 Security 上下文读取已经校验完成的用户信息
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        LoginUser loginUser = authentication != null
                && authentication.getPrincipal() instanceof LoginUser user
                ? user
                : null;

        String ipHash = sha256(request.getRemoteAddr());
        // 判断是否匿名：loginUser为空=未登录游客
        boolean anonymous = loginUser == null;
        String viewerKey = anonymous
                ? "ip:" + ipHash
                : "user:" + loginUser.userId();

        // 记录浏览
        return ApiResponse.success(
                videoService.recordView(id, viewerKey, ipHash, anonymous)
        );
    }

    /** 将原始 IP 截断哈希，避免把可识别的地址作为播放去重键存储或传递。 */
    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    // 把字符串按 UTF-8 编码转成字节数组，再算出 32 字节的摘要
                    .digest(value.getBytes(StandardCharsets.UTF_8));
                    // 从摘要的第 0 个字节开始，取 12 个字节，转成十六进制字符串返回
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

}
