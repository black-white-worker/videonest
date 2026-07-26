package com.example.demo.module.follow.controller;

import com.example.demo.common.api.ApiResponse;
import com.example.demo.common.api.PageResult;
import com.example.demo.module.follow.service.FollowService;
import com.example.demo.module.follow.vo.FollowStatusVO;
import com.example.demo.module.follow.vo.FollowUserVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/users")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PostMapping("/{userId}/follow")
    public ApiResponse<Void> follow(@PathVariable @Min(1) Long userId) {
        followService.follow(userId);
        return ApiResponse.success();
    }

    @DeleteMapping("/{userId}/follow")
    public ApiResponse<Void> unfollow(@PathVariable @Min(1) Long userId) {
        followService.unfollow(userId);
        return ApiResponse.success();
    }

    @GetMapping("/{userId}/follow/status")
    public ApiResponse<FollowStatusVO> getFollowStatus(@PathVariable @Min(1) Long userId) {
        return ApiResponse.success(followService.getFollowStatus(userId));
    }

    @GetMapping("/me/following")
    public ApiResponse<PageResult<FollowUserVO>> listMyFollowing(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.success(followService.listMyFollowing(page, size));
    }

    @GetMapping("/me/followers")
    public ApiResponse<PageResult<FollowUserVO>> listMyFollowers(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.success(followService.listMyFollowers(page, size));
    }
}
