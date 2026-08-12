package com.videonest.module.interaction.mapper;

import com.videonest.module.interaction.entity.VideoLike;
import org.apache.ibatis.annotations.*;

@Mapper
public interface VideoLikeMapper {

    /**
     * 根据用户ID和视频ID统计点赞记录数量
     * 用途：判断当前用户是否已经给该视频点过赞
     * @param userId 用户主键ID
     * @param videoId 视频主键ID
     * @return 点赞记录条数 0=未点赞 1=已点赞
     */
    @Select("""
            SELECT COUNT(1)
            FROM video_like
            WHERE user_id = #{userId}
            AND video_id = #{videoId}
            """)                        //COUNT(1)总条数
    int countByUserIdAndVideoId(
            @Param("userId") Long userId,
            @Param("videoId") Long videoId
    );

    /**
     * 新增一条点赞记录
     * @param videoLike 点赞实体对象，封装了userId和videoId
     * @return 数据库受影响行数，1代表插入成功，0代表失败
     */
    @Insert("""
            INSERT INTO video_like (user_id, video_id)
            VALUES (#{userId}, #{videoId})
            """)
    // 入参为VideoLike实体，MyBatis通过反射获取实体的userId、videoId属性填充SQL
    int insert(VideoLike videoLike);

    /**
     * 取消点赞，根据用户ID+视频ID删除点赞记录
     * @param userId 用户ID
     * @param videoId 视频ID
     * @return 受影响行数，1删除成功，0无数据可删
     */
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