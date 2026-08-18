package com.videonest.module.video.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video")
public class Video {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long authorId;

    private Long categoryId;

    private String title;

    private String description;

    // 对外展示的封面图访问地址（经过处理后的封面）
    private String coverUrl;

    private String originalCoverUrl;

    // 列表页专用封面地址，首页/分类列表缩略小封面
    private String coverListUrl;

    // 详情页专用封面地址，视频播放页大图封面
    private String coverDetailUrl;

    private String videoUrl;

    private Integer duration;

    private String status;

    private Long viewCount;

    private Long likeCount;

    private Long favoriteCount;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String rejectReason;

    private String originalVideoUrl;

    @TableField("video_480p_url")
    private String video480pUrl;

    @TableField("video_720p_url")
    private String video720pUrl;

    @TableField("video_1080p_url")
    private String video1080pUrl;

    private String processError;

    @TableLogic(value = "0", delval = "1")
    private Integer isDeleted;

    private LocalDateTime deletedAt;

    private Long deletedBy;

    // 彻底删除时间点：软删除之后，到达这个时间，后台定时任务执行purge彻底删除资源文件+数据库记录
    private LocalDateTime purgeAfter;

    // 彻底删除任务重试次数；删除对象存储资源失败时，记录重试多少次
    private Integer purgeAttempts;

    // 彻底删除报错信息，清除文件失败保存错误日志
    private String purgeError;

    private LocalDateTime reviewDeadline;

    private Integer reviewTimeoutNotified;

}
