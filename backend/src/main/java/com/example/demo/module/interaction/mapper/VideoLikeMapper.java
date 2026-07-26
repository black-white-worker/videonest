package com.example.demo.module.interaction.mapper;

import com.example.demo.module.interaction.entity.VideoLike;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VideoLikeMapper {

    @Select("""
            SELECT COUNT(1)
            FROM video_like
            WHERE user_id = #{userId}
            AND video_id = #{videoId}
            """)
    int countByUserIdAndVideoId(
            @Param("userId") Long userId,
            @Param("videoId") Long videoId
    );

    @Insert("""
            INSERT INTO video_like (user_id, video_id)
            VALUES (#{userId}, #{videoId})
            """)
    int insert(VideoLike videoLike);

    @Delete("""
            DELETE FROM video_like
            WHERE user_id = #{userId}
            AND video_id = #{videoId}
            """)
    int deleteByUserIdAndVideoId(
            @Param("userId") Long userId,
            @Param("videoId") Long videoId
    );
}