package com.videonest.module.follow.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.follow.vo.FollowStatusVO;
import com.videonest.module.follow.vo.FollowUserVO;

/**
 * 用户关注模块业务层接口
 * 定义关注、取关、查询关注状态、分页查看关注列表/粉丝列表的抽象方法
 * 由FollowServiceImpl实现类完成数据库操作、缓存处理、参数校验等具体逻辑
 */
public interface FollowService {

    /**
     * 关注目标用户
     * @param followeeId 被关注人的用户主键ID
     */
    void follow(Long followeeId);

    /**
     * 取消对目标用户的关注
     * @param followeeId 被取消关注的用户主键ID
     */
    void unfollow(Long followeeId);

    /**
     * 获取当前登录用户与目标用户的关注关系状态
     * 例如：是否已关注对方、是否双向互相关注
     * @param followeeId 要查询关系的目标用户ID
     * @return FollowStatusVO 封装好的关注状态视图对象，返回给前端展示
     */
    FollowStatusVO getFollowStatus(Long followeeId);

    /**
     * 分页查询我自己关注的所有用户列表（我的关注）
     * @param page 请求页码，从1开始计数
     * @param size 单页展示的数据条数
     * @return PageResult分页统一返回体，内部泛型是FollowUserVO用户展示VO
     */
    PageResult<FollowUserVO> listMyFollowing(long page, long size);

    /**
     * 分页查询关注我的所有用户列表（我的粉丝）
     * @param page 请求页码，从1开始计数
     * @param size 单页展示的数据条数
     * @return PageResult分页统一返回体，内部泛型是FollowUserVO用户展示VO
     */
    PageResult<FollowUserVO> listMyFollowers(long page, long size);
}
