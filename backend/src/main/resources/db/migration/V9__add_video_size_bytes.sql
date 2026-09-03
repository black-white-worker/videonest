SET NAMES utf8mb4;

-- 转码完成时直接从本地文件获取大小写入数据库，避免用户首次访问详情页时3次 MinIO statObject 网络调用
ALTER TABLE video
    ADD COLUMN video_480p_size_bytes BIGINT NULL COMMENT '480P视频文件字节大小' AFTER video_1080p_url,
    ADD COLUMN video_720p_size_bytes BIGINT NULL COMMENT '720P视频文件字节大小' AFTER video_480p_size_bytes,
    ADD COLUMN video_1080p_size_bytes BIGINT NULL COMMENT '1080P视频文件字节大小' AFTER video_720p_size_bytes;
