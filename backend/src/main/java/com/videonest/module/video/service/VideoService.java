package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.dto.VideoCreateRequest;
import com.videonest.module.video.dto.VideoUpdateRequest;
import com.videonest.module.video.vo.*;

import java.util.List;

/**
 * VideoService 视频业务接口
 * 定义视频模块全部业务抽象：用户浏览、播放埋点、创作者管理、管理员审核管理
 */
public interface VideoService {


    /**
     * 查询已发布视频分页列表
     * @param categoryId 分类ID，可为null，不按分类筛选
     * @param keyword 搜索关键词，标题/描述模糊搜索
     * @param page 当前页码
     * @param size 每页条数
     */
    PageResult<VideoListItemVO> listPublishedVideos(
            Long categoryId,
            String keyword,
            long page,
            long size
    );

    /**
     * 获取已发布视频详情
     * @param videoId 视频主键ID
     */
    VideoDetailVO getPublishedVideoDetail(Long videoId);

    /**
     * 记录视频播放浏览埋点
     * @param videoId 被播放的视频ID
     * @param viewerKey 观看者唯一标识；登录用户可用userId，匿名用户生成随机key
     * @param ipHash 用户ip哈希值，防止同一IP重复刷播放量，不存储原始IP保护隐私
     * @param anonymous 是否匿名访问 true=未登录游客
     */
    VideoViewReportVO recordView(
            Long videoId,
            String viewerKey,
            String ipHash,
            boolean anonymous
    );

    /**
     * 获取热门视频列表
     * @param limit 返回多少条热门视频
     */
    List<VideoListItemVO> listHotVideos(int limit);

    /**
     * 创作者新建视频投稿
     * @param request 创建视频请求DTO，接收前端表单：标题、分类、简介、视频文件、封面等
     */
    VideoCreateVO createVideo(VideoCreateRequest request);

    /**
     * 管理员分页查询待审核视频列表
     * @param page 页码
     * @param size 每页大小
     */
    PageResult<AdminVideoReviewVO> listPendingReviewVideos(
            long page,
            long size
    );

    /**
     * 管理员执行视频审核操作
     * @param videoId 需要审核的视频ID
     * @param action 审核动作：pass通过 / reject驳回
     * @param rejectReason 驳回原因，如果action为pass，该值可为null
     */
    void reviewVideo(
            Long videoId,
            String action,
            String rejectReason
    );

    /**
     * 创作者查询自己投稿的全部视频（包含待审核、已发布、驳回）
     * @param page 页码
     * @param size 每页条数
     */
    PageResult<CreatorVideoListVO> listCreatorVideos(
            long page,
            long size
    );

    /**
     * 获取当前登录创作者的个人主页资料
     */
    CreatorProfileVO getCreatorProfile();

    /**
     * 分页查询我点赞过的视频
     */
    PageResult<VideoListItemVO> listMyLikedVideos(long page, long size);

    /**
     * 分页查询我收藏的视频
     */
    PageResult<VideoListItemVO> listMyFavoritedVideos(long page, long size);

    /**
     * 创作者修改自己的视频
     * 修改后视频会重新进入审核流程
     * @param videoId 视频id
     * @param request 更新请求dto，前端提交的修改字段
     */
    void updateCreatorVideo(Long videoId, VideoUpdateRequest request);

    /**
     * 【创作者删除自己的视频】
     * @param videoId 视频主键
     */
    void deleteCreatorVideo(Long videoId);

    /**
     * 管理员修改任意视频，管理员后台强制编辑视频，不受创作者权限限制
     * @param videoId 视频ID
     * @param request 更新参数DTO
     */
    void updateAdminVideo(Long videoId, VideoUpdateRequest request);

    /**
     * 管理员强制删除视频，后台管理端删除，可删除任意用户视频
     * @param videoId 视频ID
     */
    void deleteAdminVideo(Long videoId);


}
