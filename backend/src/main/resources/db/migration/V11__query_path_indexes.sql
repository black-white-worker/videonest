SET NAMES utf8mb4;

-- 本迁移只补“过滤列 + 排序列”能连成一条索引的查询路径，不改变任何业务语义。
-- 判定依据是 Mapper 里真实出现的 WHERE / ORDER BY，见 sql/2026-11-06-explain-checks.sql。

-- 1) 转码超时兜底扫描：
--    WHERE status='PROCESSING' AND is_deleted=0 AND update_time <= ?
--    ORDER BY update_time ASC LIMIT ?
--    原索引 (status, publish_time) / (status, is_deleted, publish_time, id) 都不含 update_time，
--    只能先扫出全部 PROCESSING 行再 filesort。该任务每 5 分钟跑一次，视频表越大越慢。
ALTER TABLE video
    ADD KEY idx_video_status_deleted_update (status, is_deleted, update_time);

-- 2) 回收站分页：
--    WHERE is_deleted=1 ORDER BY deleted_at DESC, id DESC
--    已有 idx_video_deleted_purge (is_deleted, purge_after)，排序列不同，仍会 filesort。
ALTER TABLE video
    ADD KEY idx_video_deleted_at (is_deleted, deleted_at, id);

-- 3) 创作者主页状态统计：
--    WHERE author_id=? AND is_deleted=0 [AND status=?]
--    单列 idx_video_author(author_id) 可以定位但需要回表逐行判 status；
--    (author_id, status) 让 4 个计数合并成一次索引扫描（见 CreatorVideoQueryServiceImpl）。
ALTER TABLE video
    ADD KEY idx_video_author_status (author_id, status);

-- 4) 一级评论级联删除：
--    WHERE root_id=? AND status=1
--    已有索引都以 video_id / parent_id 开头，root_id 上没有可用前缀，删除一条根评论会全表扫。
ALTER TABLE video_comment
    ADD KEY idx_comment_root_status (root_id, status);
