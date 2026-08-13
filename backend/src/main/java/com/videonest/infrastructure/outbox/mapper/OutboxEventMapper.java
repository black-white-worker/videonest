package com.videonest.infrastructure.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videonest.infrastructure.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * OutboxEvent Mapper持久层接口
 * 负责操作outbox_event发件箱消息表所有自定义数据库操作
 * 配合事务发件箱Dispatcher分发器完成消息查询、抢占锁定、状态更新、异常恢复
 */
/*
* 以一张outbox_event数据表为核心，通过状态机流转 + 数据库原子锁抢占 + 指数退避重试 + 超时自动恢复
* */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * @ Select 注解：自定义查询SQL
     * 功能：查询当前满足条件、可以立即执行投递的待发送消息
     * 筛选条件：状态为待发送PENDING 或 上次发送失败FAILED，且到达了下次重试时间
     * 按主键id升序排序，限制查询条数，避免一次性加载过多数据压垮数据库
     * @param limit 单次批量查询最大条数
     * @return 可投递的发件箱事件列表
     */
    @Select("""
            SELECT * FROM outbox_event
            WHERE status IN ('PENDING', 'FAILED')
              AND next_retry_at <= NOW()
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> selectReady(@Param("limit") int limit);

    /**
     * 抢占锁定一条待发送消息（分布式防重复发送）
     * 将符合条件的消息状态改为PROCESSING处理中，相当于数据库悲观锁抢占资源
     * 只有原本是PENDING/FAILED且到重试时间的数据才能被抢占，返回受影响行数
     * 多服务实例并发轮询时，只有一个实例能更新成功这条数据，杜绝重复投递MQ
     * @param id 待抢占的消息主键ID
     * @return 数据库受影响行数，1=抢占成功，0=已被其他实例抢占或不符合条件
     */
    @Update("""
            UPDATE outbox_event
            SET status = 'PROCESSING', updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('PENDING', 'FAILED')
              AND next_retry_at <= NOW()
            """)
    int claim(@Param("id") Long id);

    /**
     * 标记消息投递成功
     * 仅当当前状态为PROCESSING（正在处理）时执行更新，防止状态乱改
     * 更新状态为SENT已发送，记录发送完成时间，清空上次错误信息
     * @param id 消息主键ID
     * @return 受影响行数
     */
    @Update("""
            UPDATE outbox_event
            SET status = 'SENT', sent_at = NOW(), last_error = NULL
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int markSent(@Param("id") Long id);

    /**
     * 标记消息投递失败
     * 投递异常时调用：状态改为FAILED失败，重试次数+1
     * 设置下次重试时间、记录本次报错详情，用于后续重试排查
     * 同样强校验必须是PROCESSING状态才能更新，保证状态流转闭环
     * @param id 消息主键
     * @param nextRetryAt 下一次定时重试的时间点
     * @param lastError 异常堆栈/错误描述字符串
     * @return 受影响行数
     */
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

    /**
     * 异常僵死数据自动恢复兜底SQL（容灾核心）
     * 场景：分发器执行到一半宕机、服务被杀、网络中断，消息卡在PROCESSING处理中永远无法更新状态
     * 规则：找到状态为PROCESSING、且5分钟内没有更新过的过期数据
     * 重置为FAILED失败状态，立即允许下次重试，避免消息永久卡死无法投递
     * @return 恢复的僵死记录行数
     */
    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED', next_retry_at = NOW(),
                last_error = '发送进程中断，已自动恢复'
            WHERE status = 'PROCESSING'
              AND updated_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)
            """)
    int recoverStaleProcessing();
}
