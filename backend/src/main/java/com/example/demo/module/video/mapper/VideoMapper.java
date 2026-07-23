package com.example.demo.module.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.demo.module.video.entity.Video;
import com.example.demo.module.video.vo.VideoListItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {

    @Select("""
            SELECT
                v.id,
                v.title,
                v.description,
                v.cover_url AS coverUrl,
                v.duration,
                v.view_count AS viewCount,
                v.like_count AS likeCount,
                v.favorite_count AS favoriteCount,
                v.publish_time AS publishTime,
                v.author_id AS authorId,
                u.nickname AS authorNickname,
                v.category_id AS categoryId,
                c.name AS categoryName
            FROM video v
            INNER JOIN sys_user u ON v.author_id = u.id
            INNER JOIN video_category c ON v.category_id = c.id
            WHERE v.status = 'PUBLISHED'
              AND (#{categoryId} IS NULL OR v.category_id = #{categoryId})
            ORDER BY v.publish_time DESC, v.id DESC
            """)
    IPage<VideoListItemVO> selectPublishedPage(
            Page<VideoListItemVO> page,
            @Param("categoryId") Long categoryId
    );
}