package com.videonest.module.video.event;

/**
 * 资源彻底删除领域事件的外层包装类
 * event：ResourcePurgeEvent，封装业务数据
 * delayMilliseconds：延迟多少毫秒后再消费这条事件

 * 1. 业务本地事务完成（video记录is_deleted更新为1软删除）
 * 2. 构建 ResourcePurgeDomainEvent，传入事件实体 + 延迟时间
 * 3. Outbox把这个事件存入outbox消息表，标记延迟时间，不立刻发MQ
 * 4. 消息投递器读到delayMilliseconds，发送RabbitMQ延迟消息；或者定时任务轮询outbox等到时间到再投递
 * 5. 消费者收到 ResourcePurgeEvent，执行删除MinIO上的视频、封面资源，更新video表purge相关字段
 */
public record ResourcePurgeDomainEvent(
        ResourcePurgeEvent event,
        long delayMilliseconds
) {
}
