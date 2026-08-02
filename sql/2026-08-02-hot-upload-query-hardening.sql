SET NAMES utf8mb4;
USE videonest;

-- 首页、分区、作者页与回收任务的主要过滤/排序路径。
ALTER TABLE video
    ADD KEY idx_video_publish_feed (status, is_deleted, publish_time, id),
    ADD KEY idx_video_category_feed (category_id, status, is_deleted, publish_time, id),
    ADD KEY idx_video_author_feed (author_id, is_deleted, create_time, id),
    ADD UNIQUE KEY uk_video_original_object (original_video_url);

-- 一级评论分页、回复计数/列表均可命中组合索引。
ALTER TABLE video_comment
    ADD KEY idx_comment_video_parent_status_time
        (video_id, parent_id, status, created_at, id),
    ADD KEY idx_comment_parent_status (parent_id, status);

-- “我的点赞/收藏”按关系 ID 倒序分页。
ALTER TABLE video_like
    ADD KEY idx_like_user_page (user_id, id, video_id);

ALTER TABLE video_favorite
    ADD KEY idx_favorite_user_page (user_id, id, video_id);
