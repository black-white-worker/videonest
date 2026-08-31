SET NAMES utf8mb4;

CREATE TABLE password_reset_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED',
    handled_by BIGINT NULL,
    handled_at DATETIME NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_password_reset_status_time (status, create_time),
    KEY idx_password_reset_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密码重置申请';
