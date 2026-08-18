package com.videonest.module.video.event;

/**
 * 领域事件记录类（Record），专门用来表达：某个视频审核已经超时，需要做后续处理。
 */
public record ReviewTimeoutEvent(Long videoId) {
}
