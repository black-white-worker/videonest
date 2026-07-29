SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS videonest
DEFAULT CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE videonest;

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

INSERT INTO video_category (id, name, sort_num, status) VALUES
    (1, '动画', 1, 1),
    (2, '音乐', 2, 1),
    (3, '游戏', 3, 1),
    (4, '知识', 4, 1),
    (5, '生活', 5, 1),
    (6, '科技', 6, 1);


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

CREATE TABLE video_like (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    video_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_video_like (user_id, video_id),
    KEY idx_video_id (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_favorite (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    video_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_video_favorite (user_id, video_id),
    KEY idx_video_id (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE video_comment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    video_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '0代表一级评论',
    content VARCHAR(500) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常，0已删除',
    deleted_at DATETIME NULL COMMENT '软删除时间，NULL表示未删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_video_created (video_id, created_at),
    KEY idx_comment_deleted_at (deleted_at),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL COMMENT '发起关注的用户 ID',
    followee_id BIGINT NOT NULL COMMENT '被关注的用户 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follower_followee (follower_id, followee_id),
    KEY idx_followee_created (followee_id, created_at),
    KEY idx_follower_created (follower_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';

USE videonest;

CREATE TABLE IF NOT EXISTS user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL COMMENT '发起关注的用户 ID',
    followee_id BIGINT NOT NULL COMMENT '被关注的用户 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follower_followee (follower_id, followee_id),
    KEY idx_followee_created (followee_id, created_at),
    KEY idx_follower_created (follower_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注关系表';

ALTER TABLE video
    ADD COLUMN original_video_url VARCHAR(500) NULL COMMENT '用户上传的原始视频对象名' AFTER video_url,
    ADD COLUMN video_480p_url VARCHAR(500) NULL COMMENT '480P 视频对象名' AFTER original_video_url,
    ADD COLUMN video_720p_url VARCHAR(500) NULL COMMENT '720P 视频对象名' AFTER video_480p_url,
    ADD COLUMN video_1080p_url VARCHAR(500) NULL COMMENT '1080P 视频对象名' AFTER video_720p_url,
    ADD COLUMN process_error VARCHAR(1000) NULL COMMENT '转码失败原因' AFTER reject_reason;




