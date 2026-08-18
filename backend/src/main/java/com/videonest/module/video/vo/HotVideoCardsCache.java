package com.videonest.module.video.vo;

import java.util.List;

/**
 * HotVideoCardsCache 这个 Record 类，里面就专门存放 List<VideoListItemVO>
 * */
public record HotVideoCardsCache(List<VideoListItemVO> videos) {
}
