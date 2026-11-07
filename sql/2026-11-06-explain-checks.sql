-- VideoNest 查询路径校验脚本（MySQL 8.0）
-- 用法：在 MySQL 客户端里整段执行，逐条看 key / rows / Extra 三列。
--   key     期望命中索引；出现 NULL 说明退化为全表扫描。
--   rows    预计扫描行数，应接近返回行数，而不是整表行数。
--   Extra   出现 "Using filesort" 说明 ORDER BY 没有走索引。
-- 脚本只读，不修改数据。请把注释里的示例 ID 换成库中真实存在的值。

USE videonest;

-- ---------------------------------------------------------------
-- 0. 先看数据量与索引分布
-- ---------------------------------------------------------------
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('video', 'video_comment', 'video_like', 'video_favorite',
                     'user_follow', 'notification', 'outbox_event');

SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('video', 'video_comment')
GROUP BY table_name, index_name
ORDER BY table_name, index_name;

-- ---------------------------------------------------------------
-- 1. 视频列表（首页 / 分类页）——核心读路径
--    期望：key = idx_video_publish_feed 或 idx_video_category_feed
--          Extra 不含 "Using filesort"
-- ---------------------------------------------------------------
EXPLAIN
SELECT v.id, v.title, v.publish_time
FROM video v
INNER JOIN sys_user u ON v.author_id = u.id
INNER JOIN video_category c ON v.category_id = c.id
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0
ORDER BY v.publish_time DESC, v.id DESC
LIMIT 12;

-- 分类筛选版本（分类 ID 1 = 动画）
EXPLAIN
SELECT v.id, v.title, v.publish_time
FROM video v
INNER JOIN sys_user u ON v.author_id = u.id
INNER JOIN video_category c ON v.category_id = c.id
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0 AND v.category_id = 1
ORDER BY v.publish_time DESC, v.id DESC
LIMIT 12;

-- 列表总数：期望走同一套索引的范围扫描，而不是全表 + join 之后再 count
EXPLAIN
SELECT COUNT(*)
FROM video v
INNER JOIN sys_user u ON v.author_id = u.id
INNER JOIN video_category c ON v.category_id = c.id
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0;

-- 关键词搜索：当前 SQL 是 LIKE '%kw%'，必然 type=ALL + filesort。
-- 这一条命中 ALL 是预期结果，用来证明“搜索需要独立索引/引擎”，不是回归。
EXPLAIN
SELECT v.id FROM video v
WHERE v.status = 'PUBLISHED' AND v.is_deleted = 0 AND v.title LIKE '%Spring%'
ORDER BY v.publish_time DESC, v.id DESC LIMIT 12;

-- ---------------------------------------------------------------
-- 2. 评论列表（视频 ID 13 为示例，换成真实值）
--    期望：idx_comment_video_parent_status_time
-- ---------------------------------------------------------------
EXPLAIN
SELECT vc.id, vc.content, u.nickname
FROM video_comment vc
INNER JOIN sys_user u ON vc.user_id = u.id
WHERE vc.video_id = 13 AND vc.parent_id = 0 AND vc.status = 1
ORDER BY vc.created_at DESC, vc.id DESC
LIMIT 10;

-- 当前页根评论的回复数：期望 idx_comment_video_root_status_time，Using index
EXPLAIN
SELECT root_id, COUNT(1)
FROM video_comment
WHERE video_id = 13 AND status = 1 AND root_id IN (100, 101, 102)
GROUP BY root_id;

-- 某条根评论下的回复分页：期望 idx_comment_video_root_status_time
EXPLAIN
SELECT vc.id, vc.content
FROM video_comment vc
WHERE vc.video_id = 13 AND vc.root_id = 100 AND vc.status = 1
ORDER BY vc.created_at ASC, vc.id ASC
LIMIT 10;

-- 删除根评论时级联软删：期望 idx_comment_root_status（V11 新增）
EXPLAIN
UPDATE video_comment SET status = 0 WHERE root_id = 100 AND status = 1;

-- ---------------------------------------------------------------
-- 3. 通知中心
--    期望：idx_notification_recipient_read_time，无 filesort
-- ---------------------------------------------------------------
EXPLAIN
SELECT n.id, n.type, n.create_time
FROM notification n
WHERE n.recipient_id = 1
ORDER BY n.is_read ASC, n.create_time DESC, n.id DESC
LIMIT 20;

EXPLAIN
SELECT COUNT(*) FROM notification WHERE recipient_id = 1 AND is_read = 0;

-- ---------------------------------------------------------------
-- 4. 关注 / 粉丝列表
--    期望：idx_follower_created / idx_followee_created
-- ---------------------------------------------------------------
EXPLAIN
SELECT u.id, u.nickname, uf.created_at
FROM user_follow uf
INNER JOIN sys_user u ON u.id = uf.followee_id
WHERE uf.follower_id = 1 AND u.status = 1
ORDER BY uf.created_at DESC, uf.id DESC
LIMIT 20;

-- ---------------------------------------------------------------
-- 5. 创作者后台
--    期望：idx_video_author_feed（列表）、idx_video_author_status（计数）
-- ---------------------------------------------------------------
EXPLAIN
SELECT v.id, v.title, v.status, v.create_time
FROM video v
LEFT JOIN video_category c ON v.category_id = c.id
WHERE v.author_id = 1 AND v.is_deleted = 0
ORDER BY v.create_time DESC, v.id DESC
LIMIT 20;

-- 改造后的单条统计 SQL（原来是 4 条独立 COUNT）
EXPLAIN
SELECT COUNT(*) AS total,
       COALESCE(SUM(status = 'PENDING'), 0)   AS pending,
       COALESCE(SUM(status = 'PUBLISHED'), 0) AS published,
       COALESCE(SUM(status = 'REJECTED'), 0)  AS rejected
FROM video
WHERE author_id = 1 AND is_deleted = 0;

-- ---------------------------------------------------------------
-- 6. 后台任务扫描
-- ---------------------------------------------------------------
-- 转码超时兜底：期望 idx_video_status_deleted_update
EXPLAIN
SELECT id FROM video
WHERE status = 'PROCESSING' AND is_deleted = 0
  AND update_time <= DATE_SUB(NOW(), INTERVAL 30 MINUTE)
ORDER BY update_time ASC
LIMIT 20;

-- 待物理清理：期望 idx_video_deleted_purge
EXPLAIN
SELECT id FROM video
WHERE is_deleted = 1 AND purge_after IS NOT NULL AND purge_after <= NOW()
ORDER BY purge_after ASC
LIMIT 20;

-- 回收站分页：期望 idx_video_deleted_at（V11 新增）
EXPLAIN
SELECT v.id, v.title, v.deleted_at
FROM video v
WHERE v.is_deleted = 1
ORDER BY v.deleted_at DESC, v.id DESC
LIMIT 20;

-- 审核超时：期望 idx_video_review_timeout
EXPLAIN
SELECT id FROM video
WHERE status = 'PENDING' AND is_deleted = 0
  AND review_deadline IS NOT NULL AND review_deadline <= NOW()
  AND review_timeout_notified = 0
ORDER BY review_deadline ASC
LIMIT 50;

-- ---------------------------------------------------------------
-- 7. Outbox 派发：期望 idx_outbox_pending
-- ---------------------------------------------------------------
EXPLAIN
SELECT * FROM outbox_event
WHERE status IN ('PENDING', 'FAILED') AND next_retry_at <= NOW()
ORDER BY id
LIMIT 50;

-- ---------------------------------------------------------------
-- 8. 复合索引使用率（可选）
--    索引写了却从没被读到，通常是历史遗留；确认后可手工 DROP 以降低写放大：
--    idx_video_category_publish / idx_video_status_publish 已被 V4 的 feed 索引覆盖。
-- ---------------------------------------------------------------
SELECT * FROM sys.schema_unused_indexes
WHERE object_schema = DATABASE();
