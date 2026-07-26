package com.example.demo.module.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.vo.AdminVideoReviewVO;
import com.example.demo.module.video.vo.CreatorVideoListVO;
import com.example.demo.module.video.vo.VideoDetailVO;
import com.example.demo.module.video.vo.VideoListItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    IPage<VideoListItemVO> selectPublishedPage(
            Page<VideoListItemVO> page,
            @Param("categoryId") Long categoryId
    );

    int increaseViewCount(@Param("videoId") Long videoId);

    VideoDetailVO selectPublishedDetailById(@Param("videoId") Long videoId);

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

    int updateVideoById(Video video);

    int deleteVideoById(Long videoId);

    int changeLikeCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );

    int changeFavoriteCount(
            @Param("videoId") Long videoId,
            @Param("delta") int delta
    );


}
