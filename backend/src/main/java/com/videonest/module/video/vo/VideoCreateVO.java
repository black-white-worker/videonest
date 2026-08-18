package com.videonest.module.video.vo;

/**
 * VO：视频创建响应VO
 * 业务场景：用户上传视频，调用创建接口，接口返回这个对象给前端
 * 用户刚提交视频，视频进入待审核状态，前端拿到videoId，就可以去查看、编辑这个刚上传的视频
 * 只返回少量必要字段，不返回完整视频全部信息
 */
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class VideoCreateVO {

    private Long videoId;

    private String status;

    private String rejectReason;

}