package com.example.demo.module.follow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.common.api.PageResult;
import com.example.demo.common.exception.BusinessException;
import com.example.demo.infrastructure.redis.RedisKeys;
import com.example.demo.module.follow.entity.UserFollow;
import com.example.demo.module.follow.mapper.UserFollowMapper;
import com.example.demo.module.follow.service.FollowService;
import com.example.demo.module.follow.vo.FollowStatusVO;
import com.example.demo.module.follow.vo.FollowUserVO;
import com.example.demo.module.user.entity.SysUser;
import com.example.demo.module.user.mapper.SysUserMapper;
import com.example.demo.security.LoginUser;
import com.example.demo.security.SecurityUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.example.demo.module.notification.event.NotificationDomainEvent;
import com.example.demo.module.notification.event.NotificationEvent;

@Service
public class FollowServiceImpl implements FollowService {

    private final UserFollowMapper userFollowMapper;
    private final SysUserMapper sysUserMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public FollowServiceImpl(UserFollowMapper userFollowMapper,
                             SysUserMapper sysUserMapper,
                             RedisTemplate<String, Object> redisTemplate,
                             ApplicationEventPublisher eventPublisher) {
        this.userFollowMapper = userFollowMapper;
        this.sysUserMapper = sysUserMapper;
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void follow(Long followeeId) {
        Long followerId = SecurityUtils.getCurrentUser().userId();
        validateFollowee(followerId, followeeId);

        if (userFollowMapper.countByFollowerIdAndFolloweeId(followerId, followeeId) > 0) {
            return;
        }

        UserFollow userFollow = new UserFollow();
        userFollow.setFollowerId(followerId);
        userFollow.setFolloweeId(followeeId);
        userFollowMapper.insert(userFollow);
        refreshStatusCache(followerId, followeeId, true);

        eventPublisher.publishEvent(
                new NotificationDomainEvent(
                        new NotificationEvent(
                                UUID.randomUUID().toString(),
                                followeeId,    // 接收通知的人：被关注者
                                followerId,    // 触发事件的人：发起关注者
                                "FOLLOW",
                                null,
                                null,
                                "关注了你"
                        )
                )
        );
    }

    @Override
    @Transactional
    public void unfollow(Long followeeId) {
        Long followerId = SecurityUtils.getCurrentUser().userId();
        int rows = userFollowMapper.deleteByFollowerIdAndFolloweeId(followerId, followeeId);
        if (rows > 0) {
            refreshStatusCache(followerId, followeeId, false);
        }
    }

    @Override
    public FollowStatusVO getFollowStatus(Long followeeId) {
        Long followerId = SecurityUtils.getCurrentUser().userId();
        if (followerId.equals(followeeId)) {
            return new FollowStatusVO(false);
        }

        String key = RedisKeys.userFollowStatus(followerId, followeeId);
        Object cachedValue = redisTemplate.opsForValue().get(key);
        if (cachedValue instanceof Boolean followed) {
            return new FollowStatusVO(followed);
        }

        boolean followed = userFollowMapper.countByFollowerIdAndFolloweeId(followerId, followeeId) > 0;
        refreshStatusCache(followerId, followeeId, followed);
        return new FollowStatusVO(followed);
    }

    @Override
    public PageResult<FollowUserVO> listMyFollowing(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        IPage<FollowUserVO> pageData = userFollowMapper.selectFollowingPage(
                new Page<>(page, size), currentUser.userId());
        return PageResult.of(pageData);
    }

    @Override
    public PageResult<FollowUserVO> listMyFollowers(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        IPage<FollowUserVO> pageData = userFollowMapper.selectFollowerPage(
                new Page<>(page, size), currentUser.userId());
        return PageResult.of(pageData);
    }

    private void validateFollowee(Long followerId, Long followeeId) {
        if (followerId.equals(followeeId)) {
            throw new BusinessException(400, "不能关注自己");
        }

        SysUser followee = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, followeeId)
                .eq(SysUser::getStatus, 1));
        if (followee == null) {
            throw new BusinessException(404, "用户不存在或已被禁用");
        }
    }

    private void refreshStatusCache(Long followerId, Long followeeId, boolean followed) {
        redisTemplate.opsForValue().set(
                RedisKeys.userFollowStatus(followerId, followeeId),
                followed,
                12,
                TimeUnit.HOURS
        );
    }
}