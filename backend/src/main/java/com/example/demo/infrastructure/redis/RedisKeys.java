package com.example.demo.infrastructure.redis;

public final class RedisKeys {

    private RedisKeys() {
    }

    /**
     * 视频详情缓存：
     * videonest:video:detail:100
     */
    public static final String VIDEO_DETAIL_PREFIX =
            "videonest:video:detail:";

    /**
     * 视频点赞数量缓存：
     * videonest:video:like:count:100
     */
    public static final String VIDEO_LIKE_COUNT_PREFIX =
            "videonest:video:like:count:";

    /**
     * 视频收藏数量缓存：
     * videonest:video:favorite:count:100
     */
    public static final String VIDEO_FAVORITE_COUNT_PREFIX =
            "videonest:video:favorite:count:";

    /**
     * 用户是否点赞视频：
     * videonest:video:like:status:100:111
     */
    public static final String VIDEO_LIKE_STATUS_PREFIX =
            "videonest:video:like:status:";

    /**
     * 用户是否收藏视频：
     * videonest:video:favorite:status:100:111
     */
    public static final String VIDEO_FAVORITE_STATUS_PREFIX =
            "videonest:video:favorite:status:";

    /**
     * 用户评论限流：
     * videonest:comment:limit:111
     */
    public static final String COMMENT_RATE_LIMIT_PREFIX =
            "videonest:comment:limit:";

    /**
     * 用户是否关注另一位用户：
     * videonest:user:follow:status:100:111
     */
    public static final String USER_FOLLOW_STATUS_PREFIX =
            "videonest:user:follow:status:";

    /**
     * 热门视频 ZSet：
     * videonest:video:hot
     */
    public static final String VIDEO_HOT_RANK_KEY =
            "videonest:video:hot";

    public static String videoDetail(Long videoId) {
        return VIDEO_DETAIL_PREFIX + videoId;
    }

    public static String videoLikeCount(Long videoId) {
        return VIDEO_LIKE_COUNT_PREFIX + videoId;
    }

    public static String videoFavoriteCount(Long videoId) {
        return VIDEO_FAVORITE_COUNT_PREFIX + videoId;
    }

    public static String videoLikeStatus(Long videoId, Long userId) {
        return VIDEO_LIKE_STATUS_PREFIX + videoId + ":" + userId;
    }

    public static String videoFavoriteStatus(Long videoId, Long userId) {
        return VIDEO_FAVORITE_STATUS_PREFIX + videoId + ":" + userId;
    }

    public static String commentRateLimit(Long userId) {
        return COMMENT_RATE_LIMIT_PREFIX + userId;
    }

    public static String userFollowStatus(Long followerId, Long followeeId) {
        return USER_FOLLOW_STATUS_PREFIX + followerId + ":" + followeeId;
    }
}
