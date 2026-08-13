package com.videonest.infrastructure.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    @Select("""
            SELECT * FROM outbox_event
            WHERE status IN ('PENDING', 'FAILED')
              AND next_retry_at <= NOW()
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> selectReady(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PROCESSING', updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('PENDING', 'FAILED')
              AND next_retry_at <= NOW()
            """)
    int claim(@Param("id") Long id);

    @Update("""
            UPDATE outbox_event
            SET status = 'SENT', sent_at = NOW(), last_error = NULL
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markSent(@Param("id") Long id);

    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED', retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt}, last_error = #{lastError}
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError
    );

    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED', next_retry_at = NOW(),
                last_error = '发送进程中断，已自动恢复'
            WHERE status = 'PROCESSING'
              AND updated_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """)
    int recoverStaleProcessing();
}
