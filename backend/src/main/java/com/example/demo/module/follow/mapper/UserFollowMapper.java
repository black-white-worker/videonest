package com.example.demo.module.follow.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.demo.module.follow.entity.UserFollow;
import com.example.demo.module.follow.vo.FollowUserVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户关注关系 Mapper层接口
 * 负责user_follow关注关联表的数据库CRUD操作
 * 注解SQL + MyBatis-Plus分页两种写法混用
 */
@Mapper
public interface UserFollowMapper {

    /**
     * 统计关注关系是否存在
     * @param followerId 关注发起人的用户ID（A用户）
     * @param followeeId 被关注人的用户ID（B用户）
     * @return 返回匹配到的记录条数，0=未关注，大于0=已关注
     */
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

    /**
     * 新增一条关注记录
     * @param userFollow 关注关系实体对象，包含followerId、followeeId
     * @return 数据库受影响行数，1=插入成功，0=插入失败
     */
    @Insert("""
            INSERT INTO user_follow (follower_id, followee_id)
            VALUES (#{followerId}, #{followeeId})
            """)
    int insert(UserFollow userFollow);

    /**
     * 取消关注：删除指定的关注关联记录
     * @param followerId 操作人（关注者ID）
     * @param followeeId 被取消关注的用户ID
     * @return 受影响行数，1=取消关注成功
     */
    @Delete("""
            DELETE FROM user_follow
            WHERE follower_id = #{followerId}
              AND followee_id = #{followeeId}
            """)
    int deleteByFollowerIdAndFolloweeId(
            @Param("followerId") Long followerId,
            @Param("followeeId") Long followeeId
    );

    /**
     * 分页查询【我关注的人】列表
     * 逻辑：查询follower_id = 当前userId 的所有记录，关联用户表组装昵称、头像到FollowUserVO
     * @param page MyBatis-Plus分页对象，封装页码、每页条数、总条数
     * @param userId 当前登录用户ID
     * @return 分页封装的VO结果
     */
    IPage<FollowUserVO> selectFollowingPage(
            IPage<FollowUserVO> page,
            @Param("userId") Long userId
    );

    /**
     * 分页查询【我的粉丝】列表
     * 逻辑：查询followee_id = 当前userId 的所有记录，关联用户表组装粉丝信息
     * @param page 分页对象
     * @param userId 当前登录用户ID
     * @return 粉丝分页VO数据
     */
    IPage<FollowUserVO> selectFollowerPage(
            IPage<FollowUserVO> page,
            @Param("userId") Long userId
    );
}
