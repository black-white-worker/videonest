package com.example.demo.infrastructure.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 死信消息记录表 Mapper
 * 负责死信记录数据库操作，继承MP基础Mapper拥有通用增删改查
 */

@Mapper
public interface DeadLetterRecordMapper extends BaseMapper<DeadLetterRecord> {

    /**
     * 更新死信记录状态：标记已处理
     * 仅当原始状态为PENDING待处理时才能更新，防止重复操作
     * @param id 死信记录主键ID
     * @param status 更新后的状态
     * @param operatorId 操作人ID
     * @return 返回受影响数据库行数，0代表更新失败（状态不匹配/数据不存在）
     */
    @Update("""
            UPDATE dead_letter_record
            SET status = #{status},
                operator_id = #{operatorId},
                handled_at = NOW(),
                update_time = NOW()
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markHandled(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("operatorId") Long operatorId
    );
}
