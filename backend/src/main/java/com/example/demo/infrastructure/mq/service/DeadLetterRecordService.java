package com.example.demo.infrastructure.mq.service;

import com.example.demo.common.api.PageResult;
import com.example.demo.infrastructure.mq.entity.DeadLetterRecord;

/**
 * 死信记录服务接口
 * 作用：定义MQ死信消息的保存、分页查询、重试、忽略的行为规范
 * 由接口实现类去完成真正数据库操作、MQ重发逻辑
 */

public interface DeadLetterRecordService {

    /**
     * 记录死信消息
     * 当消息进入死信队列时，调用该方法把失败消息持久化存入数据库，方便后续排查和处理
     * @param queueName 队列名称：是哪个队列产生的死信
     * @param messageType 消息类型：标记业务消息是什么业务（订单、支付等）
     * @param businessId 业务id：关联业务数据id，例如订单id，方便业务定位
     * @param payload 消息原始报文，MQ消息体json字符串，保存原始消息内容，重试的时候直接复用
     * @param failureReason 失败原因：记录消息消费失败异常信息，方便排查bug
     */
    void record(
            String queueName,
            String messageType,
            String businessId,
            String payload,
            String failureReason
    );

    /**
     * 分页查询死信记录列表
     * @param page 当前页码
     * @param size 每页条数
     * @param status 死信状态：待重试、已忽略、已重试成功等状态过滤条件
     * @return PageResult 分页对象，内部包含总条数、当前页数据集合 DeadLetterRecord
     */
    PageResult<DeadLetterRecord> list(long page, long size, String status);

    /**
     * 手动重试某条死信消息
     * 根据主键id，读取数据库保存的原始消息，重新投递到MQ队列再次消费
     * @param id 死信记录数据库主键id
     */
    void retry(Long id);

    /**
     * 忽略这条死信消息，不再处理
     * 修改数据库记录状态为忽略，后续不会再重试这条消息
     * @param id 死信记录数据库主键id
     */
    void ignore(Long id);
}
