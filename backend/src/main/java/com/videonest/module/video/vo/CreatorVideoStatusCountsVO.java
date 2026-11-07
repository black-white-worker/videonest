package com.videonest.module.video.vo;

import lombok.Data;

/**
 * 创作者稿件状态统计（一次聚合查询的承载对象）。
 *
 * <p>原先创作者主页用 4 条独立 COUNT 查询分别统计总数 / 待审核 / 已发布 / 驳回，
 * 每次进主页要扫 4 遍同一批作者行。改成一条带条件聚合的 SQL 后只扫一遍，
 * 且 (author_id, status) 索引可覆盖。</p>
 */
@Data
public class CreatorVideoStatusCountsVO {

    private Long total;

    private Long pending;

    private Long published;

    private Long rejected;
}
