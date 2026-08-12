package com.videonest.module.interaction.mapper;

import com.videonest.module.interaction.entity.VideoFavorite;
import org.apache.ibatis.annotations.*;

/**
 * 视频收藏 Mapper接口
 * 负责video_favorite收藏数据表的CRUD数据库操作，由MyBatis动态生成代理实现类
 */
@Mapper
public interface VideoFavoriteMapper {

    /**
     * 根据用户ID和视频ID统计收藏记录条数
     * 作用：判断某个用户是否已经收藏了某条视频，返回1代表已收藏，0代表未收藏
     * @param userId 用户唯一ID
     * @param videoId 视频唯一ID
     * @return 匹配的数据行数
     */
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

    /**
     * 新增一条视频收藏记录
     * @param videoFavorite 收藏实体类，内部封装userId和videoId
     * @return 数据库受影响的行数，成功插入返回1，失败返回0
     */
    @Insert("""
            INSERT INTO video_favorite (user_id, video_id)
            VALUES (#{userId}, #{videoId})
            """)
    int insert(VideoFavorite videoFavorite);

    /**
     * 根据用户ID+视频ID删除收藏记录
     * 用于取消收藏功能，精准删除单条收藏关系
     * @param userId 用户ID
     * @param videoId 视频ID
     * @return 受影响行数，取消成功返回1，无数据删除返回0
     */
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