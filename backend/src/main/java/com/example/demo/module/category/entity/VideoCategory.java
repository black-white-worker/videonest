package com.example.demo.module.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_category")
public class VideoCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Integer sortNum;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}