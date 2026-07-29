SET NAMES utf8mb4;

USE videonest;

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL COMMENT '消息幂等业务键',
    recipient_id BIGINT NOT NULL COMMENT '通知接收用户ID',
    actor_id BIGINT NOT NULL COMMENT '触发通知用户ID',
    type VARCHAR(32) NOT NULL COMMENT '通知类型',
    video_id BIGINT NULL,
    comment_id BIGINT NULL,
    content VARCHAR(500) NULL,
    is_read TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_event_id (event_id),
    KEY idx_notification_recipient_read_time (recipient_id, is_read, create_time),
    KEY idx_notification_video_id (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知表';

SET @notification_event_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'notification'
      AND index_name = 'uk_notification_event_id'
);
SET @notification_event_index_sql = IF(
    @notification_event_index_exists = 0,
    'ALTER TABLE notification ADD UNIQUE KEY uk_notification_event_id (event_id)',
    'SELECT 1'
);
PREPARE notification_event_index_statement FROM @notification_event_index_sql;
EXECUTE notification_event_index_statement;
DEALLOCATE PREPARE notification_event_index_statement;

CREATE TABLE IF NOT EXISTS dead_letter_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    queue_name VARCHAR(128) NOT NULL COMMENT '死信来源队列',
    message_type VARCHAR(32) NOT NULL COMMENT '业务消息类型',
    business_id VARCHAR(64) NULL COMMENT '关联业务ID',
    payload TEXT NOT NULL COMMENT '原始消息体',
    failure_reason VARCHAR(500) NULL COMMENT '死信原因',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RETRIED/IGNORED',
    operator_id BIGINT NULL COMMENT '最后处理管理员ID',
    handled_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_dead_letter_status_time (status, create_time),
    KEY idx_dead_letter_business (message_type, business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RabbitMQ死信处理记录';

ALTER TABLE video
    ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除' AFTER process_error,
    ADD COLUMN deleted_at DATETIME NULL COMMENT '进入回收站时间' AFTER is_deleted,
    ADD COLUMN deleted_by BIGINT NULL COMMENT '执行软删除的用户ID' AFTER deleted_at,
    ADD COLUMN purge_after DATETIME NULL COMMENT '允许永久清理资源的时间' AFTER deleted_by,
    ADD COLUMN purge_attempts INT NOT NULL DEFAULT 0 COMMENT '资源清理尝试次数' AFTER purge_after,
    ADD COLUMN purge_error VARCHAR(1000) NULL COMMENT '最近一次资源清理失败原因' AFTER purge_attempts,
    ADD COLUMN review_deadline DATETIME NULL COMMENT '审核超时时间' AFTER purge_error,
    ADD COLUMN review_timeout_notified TINYINT NOT NULL DEFAULT 0 COMMENT '审核超时通知标记' AFTER review_deadline,
    ADD KEY idx_video_deleted_purge (is_deleted, purge_after),
    ADD KEY idx_video_review_timeout (status, review_deadline, review_timeout_notified);
