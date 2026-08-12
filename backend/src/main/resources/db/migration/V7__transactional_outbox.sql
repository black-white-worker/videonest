CREATE TABLE outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    exchange_name VARCHAR(128) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_retry_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务消息发件箱';
