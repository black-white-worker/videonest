package com.example.demo.module.follow.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.module.follow.vo.FollowStatusVO;
import com.example.demo.module.follow.vo.FollowUserVO;

public interface FollowService {

    void follow(Long followeeId);

    void unfollow(Long followeeId);

    FollowStatusVO getFollowStatus(Long followeeId);

    PageResult<FollowUserVO> listMyFollowing(long page, long size);

    PageResult<FollowUserVO> listMyFollowers(long page, long size);
}
