package com.videonest.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videonest.module.user.entity.PasswordResetRequest;
import com.videonest.module.user.vo.PasswordResetRequestVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PasswordResetRequestMapper extends BaseMapper<PasswordResetRequest> {
    @Select("""
            SELECT r.id, r.user_id AS userId, u.username, u.nickname,
                   r.status, r.create_time AS createTime
            FROM password_reset_request r
            INNER JOIN sys_user u ON u.id = r.user_id
            WHERE r.status = 'PENDING'
            ORDER BY r.create_time ASC
            """)
    List<PasswordResetRequestVO> selectPending();

    @Update("""
            UPDATE password_reset_request
            SET status = 'COMPLETED', handled_by = #{adminId}, handled_at = NOW()
            WHERE id = #{requestId} AND status = 'PENDING'
            """)
    int complete(@Param("requestId") Long requestId, @Param("adminId") Long adminId);
}
