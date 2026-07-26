package com.example.demo.module.follow.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.demo.module.follow.entity.UserFollow;
import com.example.demo.module.follow.vo.FollowUserVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserFollowMapper {

    @Select("""
            SELECT COUNT(1)
            FROM user_follow
            WHERE follower_id = #{followerId}
              AND followee_id = #{followeeId}
            """)
    int countByFollowerIdAndFolloweeId(
            @Param("followerId") Long followerId,
            @Param("followeeId") Long followeeId
    );

    @Insert("""
            INSERT INTO user_follow (follower_id, followee_id)
            VALUES (#{followerId}, #{followeeId})
            """)
    int insert(UserFollow userFollow);

    @Delete("""
            DELETE FROM user_follow
            WHERE follower_id = #{followerId}
              AND followee_id = #{followeeId}
            """)
    int deleteByFollowerIdAndFolloweeId(
            @Param("followerId") Long followerId,
            @Param("followeeId") Long followeeId
    );

    IPage<FollowUserVO> selectFollowingPage(
            IPage<FollowUserVO> page,
            @Param("userId") Long userId
    );

    IPage<FollowUserVO> selectFollowerPage(
            IPage<FollowUserVO> page,
            @Param("userId") Long userId
    );
}
