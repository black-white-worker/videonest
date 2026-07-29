package com.example.demo.module.video.entity;

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

    private String coverUrl;

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

    private LocalDateTime purgeAfter;

    private Integer purgeAttempts;

    private String purgeError;

    private LocalDateTime reviewDeadline;

    private Integer reviewTimeoutNotified;

}
