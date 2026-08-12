-- 评论软删除时间：保留原 status 字段，兼容现有业务。
ALTER TABLE video_comment
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间，NULL 表示未删除' AFTER status,
    ADD KEY idx_comment_deleted_at (deleted_at);

-- 为历史的 status=0 评论补充删除时间；旧数据无法得到真实删除时刻，使用最后更新时间。
UPDATE video_comment
SET deleted_at = updated_at
WHERE status = 0
  AND deleted_at IS NULL;
