package com.example.demo.module.interaction.service;

import com.example.demo.module.interaction.vo.InteractionStatusVO;

/**
 * 视频交互业务接口
 * 统一管理视频点赞、取消点赞、收藏、取消收藏、查询交互状态五大核心用户互动行为
 * 具体业务逻辑由 InteractionServiceImpl 实现类完成
 */
public interface InteractionService {

    /**
     * 获取当前登录用户对指定视频的交互状态
     * @param videoId 目标视频主键ID
     * @return InteractionStatusVO 封装：是否点赞、是否收藏、点赞总数、收藏总数等信息
     */
    InteractionStatusVO getStatus(Long videoId);

    /**
     * 对视频执行点赞操作
     * @param videoId 被点赞的视频ID
     */
    void like(Long videoId);

    /**
     * 取消对视频执行点赞操作
     * @param videoId 被点赞的视频ID
     */
    void unlike(Long videoId);

    /**
     * 收藏视频
     * @param videoId 要收藏的视频ID
     */
    void favorite(Long videoId);

    /**
     * 取消收藏视频
     * @param videoId 要取消收藏的视频ID
     */
    void unfavorite(Long videoId);
}