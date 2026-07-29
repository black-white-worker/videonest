package com.example.demo.infrastructure.mq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeadLetterRecordMapper extends BaseMapper<DeadLetterRecord> {

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
