package com.example.demo.module.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.vo.AdminVideoReviewVO;
import com.example.demo.module.video.vo.CreatorVideoListVO;
import com.example.demo.module.video.vo.VideoDetailVO;
import com.example.demo.module.video.vo.VideoListItemVO;
import com.example.demo.module.video.vo.DeletedVideoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    IPage<VideoListItemVO> selectPublishedPage(
            Page<VideoListItemVO> page,
            @Param("categoryId") Long categoryId,
            @Param("keyword") String keyword
    );

    int increaseViewCounts(@Param("deltas") Map<Long, Long> deltas);

    VideoDetailVO selectPublishedDetailById(@Param("videoId") Long videoId);

    Long selectPublishedViewCountById(@Param("videoId") Long videoId);

    List<VideoListItemVO> selectRecentPublished(@Param("limit") int limit);

    List<VideoListItemVO> selectPublishedListByIds(
            @Param("videoIds") List<Long> videoIds
    );

    int insertCreatorVideo(Video video);

    IPage<AdminVideoReviewVO> selectPendingReviewPage(
            Page<AdminVideoReviewVO> page
    );

    int reviewVideo(
            @Param("videoId") Long videoId,
            @Param("status") String status,
            @Param("rejectReason") String rejectReason
    );

    IPage<CreatorVideoListVO> selectCreatorVideoPage(
            Page<CreatorVideoListVO> page,
            @Param("authorId") Long authorId
    );

    IPage<VideoListItemVO> selectMyLikedVideoPage(
            Page<VideoListItemVO> page,
            @Param("userId") Long userId
    );

    IPage<VideoListItemVO> selectMyFavoritedVideoPage(
            Page<VideoListItemVO> page,
            @Param("userId") Long userId
    );

    int updateVideoById(Video video);

    int softDeleteVideo(
            @Param("videoId") Long videoId,
            @Param("deletedBy") Long deletedBy,
            @Param("purgeAfter") LocalDateTime purgeAfter
    );

    IPage<DeletedVideoVO> selectDeletedVideoPage(Page<DeletedVideoVO> page);

    Video selectDeletedVideoById(@Param("videoId") Long videoId);

    List<Long> selectDuePurgeVideoIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    int incrementPurgeFailure(
            @Param("videoId") Long videoId,
            @Param("error") String error
    );

    int markProcessFailed(
            @Param("videoId") Long videoId,
            @Param("error") String error
    );

    int markReviewTimedOut(@Param("videoId") Long videoId);

    int hardDeleteVideoLikes(@Param("videoId") Long videoId);

    int hardDeleteVideoFavorites(@Param("videoId") Long videoId);

    int hardDeleteVideoComments(@Param("videoId") Long videoId);

    int hardDeleteVideoNotifications(@Param("videoId") Long videoId);

    int hardDeleteVideo(@Param("videoId") Long videoId);

    int changeLikeCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );

    int changeFavoriteCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );


}
