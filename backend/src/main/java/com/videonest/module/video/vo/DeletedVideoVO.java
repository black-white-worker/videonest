package com.videonest.module.video.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * VO：已删除视频视图对象
 * 只提供给管理员后台接口使用，普通用户无法访问
 * 业务逻辑：视频执行软删除，不会马上删掉数据库记录和MinIO文件；
 * 保留一段时间，由定时任务执行物理purge（彻底清除）；记录清理失败错误，方便管理员排查
 */
@Data
public class DeletedVideoVO {

    private Long id;

    private String title;

    private Long authorId;

    private String authorNickname;

    private String coverUrl;

    private String status;

    private LocalDateTime deletedAt;

    private Long deletedBy;

    // 允许彻底物理删除的时间；定时任务只有到达这个时间，才可以去删除数据库记录 + MinIO存储文件
    private LocalDateTime purgeAfter;

    // 物理清理重试次数；清理MinIO文件可能网络异常失败，记录重试多少次，避免无限循环重试
    private Integer purgeAttempts;

    // 物理清理报错信息，比如MinIO访问失败、文件不存在；管理员看到该错误，手动排查清理失败的视频
    private String purgeError;
}
