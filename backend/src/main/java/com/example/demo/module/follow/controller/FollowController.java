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

/**
 * 用户关注、取关、查询关注状态、我的关注列表、我的粉丝列表控制器
 * 处理用户之间的关注关系相关所有HTTP接口请求
 */
@RestController
@Validated
@RequestMapping("/api/users")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /**
     * 关注指定用户接口
     * 请求方式：POST
     * 接口地址：/api/users/{userId}/follow
     * @param userId 被关注人的用户ID，最小限制为1，不能传0或负数
     * @return 统一成功返回体，无返回数据
     */
    @PostMapping("/{userId}/follow")
    public ApiResponse<Void> follow(@PathVariable @Min(1) Long userId) {
        followService.follow(userId);
        return ApiResponse.success();
    }

    /**
     * 取消关注用户接口
     * 请求方式：DELETE
     * 接口地址：/api/users/{userId}/follow
     * @param userId 要取消关注的目标用户ID
     * @return 统一成功返回体
     */
    @DeleteMapping("/{userId}/follow")
    public ApiResponse<Void> unfollow(@PathVariable @Min(1) Long userId) {
        followService.unfollow(userId);
        return ApiResponse.success();
    }

    /**
     * 查询当前登录用户与目标用户的互相关注状态
     * 请求方式：GET
     * 接口地址：/api/users/{userId}/follow/status
     * @param userId 待查询的目标用户ID
     * @return 封装关注状态的VO对象（是否关注对方、是否被对方关注）
     */
    @GetMapping("/{userId}/follow/status")
    public ApiResponse<FollowStatusVO> getFollowStatus(@PathVariable @Min(1) Long userId) {
        return ApiResponse.success(followService.getFollowStatus(userId));
    }

    /**
     * 查询我关注的人列表（分页）
     * 请求方式：GET
     * 接口地址：/api/users/me/following
     * @param page 当前页码，默认值1，最小不能小于1
     * @param size 每页条数，默认10，最小1，最大限制100防止超大分页拖垮数据库
     * @return 分页结果对象，内部装载关注用户信息VO集合
     */
    @GetMapping("/me/following")
    public ApiResponse<PageResult<FollowUserVO>> listMyFollowing(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.success(followService.listMyFollowing(page, size));
    }

    /**
     * 查询我的粉丝列表（分页）
     * 请求方式：GET
     * 接口地址：/api/users/me/followers
     * @param page 当前页码
     * @param size 每页数据条数
     * @return 分页粉丝用户VO列表
     */
    @GetMapping("/me/followers")
    public ApiResponse<PageResult<FollowUserVO>> listMyFollowers(
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) long size) {
        return ApiResponse.success(followService.listMyFollowers(page, size));
    }
}
