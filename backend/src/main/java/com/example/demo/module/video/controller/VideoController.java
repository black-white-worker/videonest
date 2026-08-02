package com.example.demo.module.video.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.video.service.VideoService;
import com.example.demo.module.video.vo.VideoDetailVO;
import com.example.demo.module.video.vo.VideoListItemVO;
import com.example.demo.module.video.vo.VideoViewReportVO;
import com.example.demo.security.LoginUser;
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

@RestController
@Validated
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;

    public VideoController(VideoService videoService) {
        this.videoService = videoService;
    }

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

    @GetMapping("/hot")
    public ApiResponse<List<VideoListItemVO>> hot(
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "热榜数量必须大于 0")
            @Max(value = 50, message = "热榜数量不能超过 50")
            int limit
    ) {
        return ApiResponse.success(videoService.listHotVideos(limit));
    }

    @PostMapping("/{id}/views")
    public ApiResponse<VideoViewReportVO> reportView(
            @PathVariable @Min(1) Long id,
            HttpServletRequest request
    ) {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        LoginUser loginUser = authentication != null
                && authentication.getPrincipal() instanceof LoginUser user
                ? user
                : null;
        String ipHash = sha256(request.getRemoteAddr());
        boolean anonymous = loginUser == null;
        String viewerKey = anonymous
                ? "ip:" + ipHash
                : "user:" + loginUser.userId();

        return ApiResponse.success(
                videoService.recordView(id, viewerKey, ipHash, anonymous)
        );
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

}
