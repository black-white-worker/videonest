-- 评论支持逻辑无限回复。脚本可重复执行，适用于初始化和远程增量部署。
DELIMITER //

CREATE PROCEDURE migrate_comment_root_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'video_comment'
          AND column_name = 'root_id'
    ) THEN
        ALTER TABLE video_comment
            ADD COLUMN root_id BIGINT NOT NULL DEFAULT 0
                COMMENT '所属一级评论ID；一级评论为0' AFTER parent_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'video_comment'
          AND column_name = 'cascade_deleted_root_id'
    ) THEN
        ALTER TABLE video_comment
            ADD COLUMN cascade_deleted_root_id BIGINT NULL
                COMMENT '因一级评论删除而被级联删除时记录根评论ID' AFTER deleted_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'video_comment'
          AND index_name = 'idx_comment_video_root_status_time'
    ) THEN
        ALTER TABLE video_comment
            ADD KEY idx_comment_video_root_status_time
                (video_id, root_id, status, created_at, id);
    END IF;

    -- 旧数据只有两层，原二级回复的 parent_id 就是所属一级评论。
    UPDATE video_comment
    SET root_id = parent_id
    WHERE parent_id <> 0
      AND root_id = 0;
END//

CALL migrate_comment_root_id()//
DROP PROCEDURE migrate_comment_root_id//

DELIMITER ;
