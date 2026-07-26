package com.example.demo.module.interaction.mapper;

import com.example.demo.module.interaction.entity.VideoFavorite;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VideoFavoriteMapper {

    @Select("""
            SELECT COUNT(1)
            FROM video_favorite
            WHERE user_id = #{userId}
            AND video_id = #{videoId}
            """)
    int countByUserIdAndVideoId(
            @Param("userId") Long userId,
            @Param("videoId") Long videoId
    );

    @Insert("""
            INSERT INTO video_favorite (user_id, video_id)
            VALUES (#{userId}, #{videoId})
            """)
    int insert(VideoFavorite videoFavorite);

    @Delete("""
            DELETE FROM video_favorite
            WHERE user_id = #{userId}
            AND video_id = #{videoId}
            """)
    int deleteByUserIdAndVideoId(
            @Param("userId") Long userId,
            @Param("videoId") Long videoId
    );
}