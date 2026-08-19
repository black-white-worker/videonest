package com.videonest.module.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.videonest.module.video.entity.Video;
import com.videonest.module.video.vo.AdminVideoReviewVO;
import com.videonest.module.video.vo.CreatorVideoListVO;
import com.videonest.module.video.vo.VideoDetailVO;
import com.videonest.module.video.vo.VideoListItemVO;
import com.videonest.module.video.vo.DeletedVideoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    /**
     * 分页查询【已发布公开视频】，返回列表卡片VO
     * @param page MyBatis‑Plus分页对象，封装页码、页大小，返回IPage<VideoListItemVO>
     * @param categoryId 分区id，可为null，按分区筛选
     * @param keyword 搜索关键词，可为null，标题/简介模糊搜索
     * @return IPage分页对象，内部是VideoListItemVO卡片数据，用于首页、分类页
     */
    IPage<VideoListItemVO> selectPublishedPage(
            Page<VideoListItemVO> page,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword
    );

    /** 独立统计视频数量，避免分页插件为 COUNT 保留作者/分类 JOIN。 */
    long countPublishedVideos(
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword
    );

    /**
     * 批量增加播放量
     * @param deltas key=videoId，value=要增加的播放量数值；批量更新，减少数据库IO，避免高频单条update
     * @return 受影响行数
     */
    int increaseViewCounts(@Param("deltas") Map<Long, Long> deltas);

    /**
     * 根据视频id查询【已发布视频详情】，多表联查返回VideoDetailVO
     * @param videoId 视频主键
     * @return VideoDetailVO 播放页详情对象
     */
    VideoDetailVO selectPublishedDetailById(@Param("videoId") Long videoId);

    /**
     * 查询已发布视频的播放量，只返回count数值
     * @param videoId 视频id
     * @return 播放量
     */
    Long selectPublishedViewCountById(@Param("videoId") Long videoId);

    /**
     * 查询最新发布的N条视频，用于首页推荐
     * @param limit 返回条数
     * @return VideoListItemVO列表
     */
    List<VideoListItemVO> selectRecentPublished(@Param("limit") int limit);

    /**
     * 根据一批videoId，批量查询视频卡片；用于Redis缓存命中后，兜底查数据库补全数据
     * @param videoIds 视频id集合
     * @return VideoListItemVO集合
     */
    List<VideoListItemVO> selectPublishedListByIds(
            @Param("videoIds") List<Long> videoIds
    );

    /**
     * 插入创作者投稿视频记录
     * @param video Video实体
     * @return 影响行数
     */
    int insertCreatorVideo(Video video);

    /**
     * 管理员分页查询【待审核视频】，返回AdminVideoReviewVO
     * @param page 分页对象
     * @return IPage<AdminVideoReviewVO>，多表联查作者、分区
     */
    IPage<AdminVideoReviewVO> selectPendingReviewPage(
            Page<AdminVideoReviewVO> page
    );

    /**
     * 执行审核操作：修改视频状态、驳回理由
     * @param videoId 视频id
     * @param status 状态 APPROVE / REJECT
     * @param rejectReason 驳回理由，通过时为null
     * @return 影响行数
     */
    int reviewVideo(
            @Param("videoId") Long videoId,
            @Param("status") String status,
            @Param("rejectReason") String rejectReason
    );

    /**
     * 分页查询当前创作者自己的全部投稿视频，返回CreatorVideoListVO
     * @param page 分页
     * @param authorId 当前登录作者id，SQL做权限过滤where author_id = #{authorId}
     * @return IPage<CreatorVideoListVO>，包含转码错误、审核、MinIO对象名等内部字段
     */
    IPage<CreatorVideoListVO> selectCreatorVideoPage(
            Page<CreatorVideoListVO> page,
            @Param("authorId") Long authorId
    );

    /**
     * 分页查询我点赞过的视频，返回卡片VO
     * @param page 分页
     * @param userId 当前登录用户id，关联点赞中间表
     * @return IPage<VideoListItemVO>
     */
    IPage<VideoListItemVO> selectMyLikedVideoPage(
            Page<VideoListItemVO> page,
            @Param("userId") Long userId
    );

    /**
     * 分页查询我收藏过的视频
     * @param page 分页
     * @param userId 当前登录用户id，关联收藏中间表
     * @return IPage<VideoListItemVO>
     */
    IPage<VideoListItemVO> selectMyFavoritedVideoPage(
            Page<VideoListItemVO> page,
            @Param("userId") Long userId
    );

    /**
     * 更新视频基础信息（创作者编辑视频标题简介分区）
     * @param video Video实体
     * @return 影响行数
     */
    int updateVideoById(Video video);

    /**
     * 视频软删除
     * @param videoId 视频id
     * @param deletedBy 执行删除操作的用户id（作者/管理员）
     * @param purgeAfter 允许物理彻底删除的时间点，定时任务到这个时间再清理
     * @return 影响行数
     */
    int softDeleteVideo(
            @Param("videoId") Long videoId,
            @Param("deletedBy") Long deletedBy,
            @Param("purgeAfter") LocalDateTime purgeAfter
    );

    /**
     * 管理员分页查询已经软删除的视频列表，返回DeletedVideoVO
     * @param page 分页对象
     * @return IPage<DeletedVideoVO>
     */
    IPage<DeletedVideoVO> selectDeletedVideoPage(Page<DeletedVideoVO> page);

    /**
     * 根据id查询已经软删除的Video实体，定时清理任务使用
     * @param videoId 视频id
     * @return Video实体
     */
    Video selectDeletedVideoById(@Param("videoId") Long videoId);

    /**
     * 查询到时间需要执行物理清理的视频id列表，定时任务调用
     * @param now 当前系统时间，
     * @param limit 防止一次性处理太多数据
     * @return 待清理视频id集合
     */
    List<Long> selectDuePurgeVideoIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /**
     * 批量查询需要回补封面缩略图的视频，后台任务批量生成封面
     * @param limit 批量条数
     * @return Video实体列表
     */
    List<Video> selectCoverThumbnailBackfillBatch(@Param("limit") int limit);

    /**
     * 清理失败：purgeAttempts计数器+1，记录错误信息purgeError
     * @param videoId 视频id
     * @param error 异常错误文本
     * @return 影响行数
     */
    int incrementPurgeFailure(
            @Param("videoId") Long videoId,
            @Param("error") String error
    );

    /**
     * 标记视频转码失败，写入processError错误信息，更新状态PROCESS_ERROR
     * @param videoId 视频id
     * @param error ffmpeg报错信息
     * @return 影响行数
     */
    int markProcessFailed(
            @Param("videoId") Long videoId,
            @Param("error") String error
    );

    /**
     * 标记视频审核超时，更新审核超时通知标记reviewTimeoutNotified=1，避免重复发通知
     */
    int markReviewTimedOut(@Param("videoId") Long videoId);

    /**
     * 物理删除：删除该视频全部点赞记录（中间表）
     */
    int hardDeleteVideoLikes(@Param("videoId") Long videoId);

    /**
     * 物理删除：删除该视频全部收藏记录
     */
    int hardDeleteVideoFavorites(@Param("videoId") Long videoId);

    /**
     * 物理删除：删除该视频全部评论
     */
    int hardDeleteVideoComments(@Param("videoId") Long videoId);

    /**
     * 物理删除：删除该视频相关通知消息
     */
    int hardDeleteVideoNotifications(@Param("videoId") Long videoId);

    /**
     * 物理删除视频主表记录，定时任务在软删除窗口期过后执行
     */
    int hardDeleteVideo(@Param("videoId") Long videoId);

    /**
     * 点赞数量原子增减，delta=+1点赞；delta=-1取消点赞；数据库层面做加减，避免并发计数错误
     * @param videoId 视频id
     * @param delta 变化量 +1 / -1
     * @return 影响行数
     */
    int changeLikeCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );

    /**
     * 收藏数量原子增减
     * @param videoId 视频id
     * @param delta +1收藏 / -1取消收藏
     * @return 影响行数
     */
    int changeFavoriteCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );


}
