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
import lombok.extern.slf4j.Slf4j;

/**
 * 关注功能业务层实现类
 * 实现用户关注、取消关注、查询关注状态、分页查询关注列表/粉丝列表
 * 集成：事务控制、Redis缓存、参数合法性校验、Spring事件发布（推送关注通知）
 */
/**
 * 关注时做自关注及用户有效性校验、幂等防重复插入并开启事务落库，操作后刷新 Redis 存储 A 对 B 的关注布尔状态，
 * 同时发布 Spring 事件由监听器异步推送关注通知；取消关注删除数据库关联记录后同步更新 Redis 为未关注状态；
 * 查询状态优先读取 Redis 缓存，未命中则查询数据库并回写缓存，分页接口借助 MyBatis-Plus
 * 分页查询后转为统一分页结果返回，Redis 以 String 类型 KV 存储双人关注关系，通过 set 方法覆盖更新缓存数据
 * */
@Service
@Slf4j
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

    /**
     * 关注目标用户
     * @param followeeId 被关注人的用户ID
     */
    @Override
    @Transactional
    public void follow(Long followeeId) {
        Long followerId = SecurityUtils.getCurrentUser().userId();
        // 前置校验：不能关注自己、被关注用户必须存在且未禁用
        validateFollowee(followerId, followeeId);

        // 判断是否已经关注过，防止重复插入重复数据
        if (userFollowMapper.countByFollowerIdAndFolloweeId(followerId, followeeId) > 0) {
            return;
        }

        // 插入数据
        UserFollow userFollow = new UserFollow();
        userFollow.setFollowerId(followerId);
        userFollow.setFolloweeId(followeeId);
        userFollowMapper.insert(userFollow);
        refreshStatusCache(followerId, followeeId, true);

        // 发布Spring领域事件，异步触发给被关注人推送系统消息通知
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
        log.info("关注用户成功，followerId={}，followeeId={}", followerId, followeeId);
    }

    /**
     * 取消关注用户
     * @param followeeId 要取关的目标用户ID
     */
    @Override
    @Transactional
    public void unfollow(Long followeeId) {
        // 获取当前登录用户ID
        Long followerId = SecurityUtils.getCurrentUser().userId();
        // Mapper执行删除：根据关注人+被关注人联合条件删除一条记录
        int rows = userFollowMapper.deleteByFollowerIdAndFolloweeId(followerId, followeeId);
        if (rows > 0) {
            // 更新Redis缓存，状态改为未关注false
            refreshStatusCache(followerId, followeeId, false);
            log.info("取消关注成功，followerId={}，followeeId={}", followerId, followeeId);
        }
    }

    /**
     * 查询当前登录用户对目标用户的关注状态（带Redis缓存优化）
     * @param followeeId 目标查询用户ID
     * @return FollowStatusVO 封装是否关注的布尔值
     */
    @Override
    public FollowStatusVO getFollowStatus(Long followeeId) {
        Long followerId = SecurityUtils.getCurrentUser().userId();
        if (followerId.equals(followeeId)) {
            return new FollowStatusVO(false);
        }

        // 拼接Redis缓存key
        String key = RedisKeys.userFollowStatus(followerId, followeeId);
        // 先查询Redis缓存
        Object cachedValue = redisTemplate.opsForValue().get(key);

        // Redis里有数据（cachedValue 不为null），存进去的数据类型是 Boolean（true/false
        // 两个条件都满足 → 缓存有效命中，直接返回，不查数据库
        if (cachedValue instanceof Boolean followed) {
            return new FollowStatusVO(followed);
        }

        // 缓存未命中，查询数据库判断关注关系
        boolean followed = userFollowMapper.countByFollowerIdAndFolloweeId(followerId, followeeId) > 0;
        // 将数据库查询结果回写到Redis，下次请求直接走缓存
        refreshStatusCache(followerId, followeeId, followed);
        return new FollowStatusVO(followed);
    }

    /**
     * 分页查询「我关注的用户」列表
     * @param page 页码
     * @param size 每页条数
     * @return 统一分页封装结果
     */
    @Override
    public PageResult<FollowUserVO> listMyFollowing(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        // MyBatis-Plus构建分页对象，调用自定义Mapper关联查询被关注用户信息
        IPage<FollowUserVO> pageData = userFollowMapper.selectFollowingPage(
                new Page<>(page, size), currentUser.userId());
        // 将MyBatis-Plus分页IPage转为项目自定义PageResult返回给Controller
        return PageResult.of(pageData);
    }

    /**
     * 分页查询「我的粉丝」列表（谁关注了我）
     */
    @Override
    public PageResult<FollowUserVO> listMyFollowers(long page, long size) {
        LoginUser currentUser = SecurityUtils.getCurrentUser();
        IPage<FollowUserVO> pageData = userFollowMapper.selectFollowerPage(
                new Page<>(page, size), currentUser.userId());
        return PageResult.of(pageData);
    }

    /**
     * 私有校验方法：关注前置规则校验
     * 1. 不能自己关注自己
     * 2. 被关注用户必须存在且状态为启用（status=1）
     */
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

    /**
     * 私有工具方法：刷新/设置Redis关注状态缓存
     * @param followerId 关注人
     * @param followeeId 被关注人
     * @param followed true=已关注 false=未关注
     */
    private void refreshStatusCache(Long followerId, Long followeeId, boolean followed) {
        redisTemplate.opsForValue().set(
                RedisKeys.userFollowStatus(followerId, followeeId),
                followed,
                12,             // 设置Redis字符串缓存，有效期12小时，过期自动重新查库
                TimeUnit.HOURS
        );
    }
}
