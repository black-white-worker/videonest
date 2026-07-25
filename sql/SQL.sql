CREATE DATABASE videonest
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(32) NOT NULL COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    nickname VARCHAR(32) NOT NULL COMMENT '昵称',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

ALTER TABLE sys_user
ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER'
COMMENT '角色：USER、ADMIN'
AFTER nickname;

CREATE TABLE IF NOT EXISTS video_category (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分区ID',
    name VARCHAR(32) NOT NULL COMMENT '分区名称',
    sort_num INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0停用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频分区表';


CREATE TABLE IF NOT EXISTS video (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '视频ID',
    author_id BIGINT NOT NULL COMMENT '投稿用户ID',
    category_id BIGINT NOT NULL COMMENT '分区ID',
    title VARCHAR(100) NOT NULL COMMENT '视频标题',
    description VARCHAR(2000) DEFAULT NULL COMMENT '视频简介',
    cover_url VARCHAR(500) DEFAULT NULL COMMENT '封面地址',
    video_url VARCHAR(500) DEFAULT NULL COMMENT '视频地址',
    duration INT NOT NULL DEFAULT 0 COMMENT '时长，单位秒',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/PUBLISHED/REJECTED',
    view_count BIGINT NOT NULL DEFAULT 0 COMMENT '播放量',
    like_count BIGINT NOT NULL DEFAULT 0 COMMENT '点赞数',
    favorite_count BIGINT NOT NULL DEFAULT 0 COMMENT '收藏数',
    publish_time DATETIME DEFAULT NULL COMMENT '发布时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_video_category_publish (category_id, publish_time),
    KEY idx_video_author (author_id),
    KEY idx_video_status_publish (status, publish_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频表';

 INSERT INTO video (
    author_id, category_id, title, description, cover_url,
      video_url, duration, status, view_count, like_count,
      favorite_count, publish_time
  ) VALUES
  (
      1, 4, 'Spring Boot 从零搭建视频平台',
      'VideoNest 项目后端开发记录。',
      'https://images.unsplash.com/photo-1516321318423-f06f85e504b3?w=800',
      'https://www.w3schools.com/html/mov_bbb.mp4',
      600, 'PUBLISHED', 128, 18, 5, NOW()
  ),
  (
      1, 6, 'Vue 3 登录注册页面开发',
      '使用 Vue 3、TypeScript、Element Plus 完成登录注册。',
      'https://images.unsplash.com/photo-1516321310764-8d7a57f6f8c3?w=800',
      'https://www.w3schools.com/html/mov_bbb.mp4',
      420, 'PUBLISHED', 96, 12, 3, NOW()
  ),
  (
      1, 5, '我的 Java 后端实习项目记录',
      '记录一个视频社区平台从零开发的过程。',
      'https://images.unsplash.com/photo-1499750310107-5fef28a66643?w=800',
      'https://www.w3schools.com/html/mov_bbb.mp4',
      300, 'PUBLISHED', 75, 9, 2, NOW()
  );
    
  UPDATE video
     SET cover_url = 'https://images.unsplash.com/photo-1499750310107-5fef28a66643?auto=format&fit=crop&w=800&q=80'
     WHERE id = 2;


  ALTER TABLE video
  ADD COLUMN reject_reason VARCHAR(500) NULL COMMENT '审核驳回原因'
  AFTER status;
