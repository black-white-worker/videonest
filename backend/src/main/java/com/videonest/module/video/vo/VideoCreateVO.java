package com.videonest.module.video.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoCreateVO {

    private Long videoId;

    private String status;

    private String rejectReason;

}