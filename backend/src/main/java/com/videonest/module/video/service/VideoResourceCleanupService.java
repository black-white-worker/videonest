package com.videonest.module.video.service;

import com.videonest.common.api.PageResult;
import com.videonest.module.video.vo.DeletedVideoVO;

/**
 * 视频资源清理服务接口
 * 业务场景：视频软删除之后的物理删除（彻底清除）相关能力
 * 视频一般先做软删除，标记为已删除，并非立刻从数据库、存储删掉；
 * 该接口负责查询已软删除视频、执行物理彻底删除、记录删除失败日志
 */
public interface VideoResourceCleanupService {

    /**
     * 分页查询【已经软删除】的视频列表
     */
    PageResult<DeletedVideoVO> listDeletedVideos(long page, long size);

    /**
     * 彻底清理（物理删除）一条视频全部资源
     */
    void purgeVideo(Long videoId);

    /**
     * 记录视频彻底清理失败的异常信息
     */
    void recordPurgeFailure(Long videoId, String error);
}
