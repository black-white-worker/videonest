package com.example.demo.module.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频分类实体类
 * 对应数据库表 video_category
 */
@Data
@TableName("video_category")
public class VideoCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    // 排序号，sort_num数据库字段，数字越小越靠前，用于前台分类展示顺序
    private Integer sortNum;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}