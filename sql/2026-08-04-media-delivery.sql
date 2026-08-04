USE videonest;

ALTER TABLE video
    ADD COLUMN original_cover_url VARCHAR(500) NULL
        COMMENT '用户上传的原始封面对象名，不直接返回给浏览器' AFTER cover_url,
    ADD COLUMN cover_list_url VARCHAR(500) NULL
        COMMENT '400px 列表封面对象名' AFTER original_cover_url,
    ADD COLUMN cover_detail_url VARCHAR(500) NULL
        COMMENT '1080px 详情封面对象名' AFTER cover_list_url;
