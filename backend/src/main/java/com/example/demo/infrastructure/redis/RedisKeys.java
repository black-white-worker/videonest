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

    public static final String VIDEO_HOT_BUCKET_PREFIX =
            "videonest:video:hot:hour:";

    public static final String VIDEO_DETAIL_LOCK_PREFIX =
            "videonest:lock:video-detail:";

    public static final String VIDEO_VIEW_DEDUP_PREFIX =
            "videonest:video:view:dedup:";

    public static final String ANONYMOUS_VIEW_RATE_PREFIX =
            "videonest:video:view:anonymous-rate:";

    public static final String UPLOAD_TICKET_PREFIX =
            "videonest:upload:ticket:";

    public static final String UPLOAD_CONFIRMED_PREFIX =
            "videonest:upload:confirmed:";

    public static final String VIDEO_VIEW_TOTAL_PREFIX =
            "videonest:video:view:total:";

    public static final String VIDEO_VIEW_DELTA_PREFIX =
            "videonest:video:view:delta:";

    public static final String VIDEO_VIEW_DIRTY_KEY =
            "videonest:video:view:dirty";

    public static final String VIDEO_PROCESS_LOCK_PREFIX =
            "videonest:lock:video-process:";

    public static final String RESOURCE_PURGE_LOCK_PREFIX =
            "videonest:lock:resource-purge:";

    public static final String RESOURCE_CLEANUP_JOB_LOCK =
            "videonest:lock:resource-cleanup-job";

    public static final String REVIEW_TIMEOUT_COUNT =
            "videonest:video:review:timeout:count";

    public static String videoDetail(Long videoId) {
        return VIDEO_DETAIL_PREFIX + videoId;
    }

    public static String videoViewTotal(Long videoId) {
        return VIDEO_VIEW_TOTAL_PREFIX + videoId;
    }

    public static String videoViewDelta(Long videoId) {
        return VIDEO_VIEW_DELTA_PREFIX + videoId;
    }

    public static String videoDetailLock(Long videoId) {
        return VIDEO_DETAIL_LOCK_PREFIX + videoId;
    }

    public static String videoViewDedup(Long videoId, String viewerKey) {
        return VIDEO_VIEW_DEDUP_PREFIX + videoId + ":" + viewerKey;
    }

    public static String anonymousViewRate(String ipHash, long window) {
        return ANONYMOUS_VIEW_RATE_PREFIX + ipHash + ":" + window;
    }

    public static String uploadTicket(String uploadId) {
        return UPLOAD_TICKET_PREFIX + uploadId;
    }

    public static String confirmedUpload(String objectName) {
        return UPLOAD_CONFIRMED_PREFIX + objectName;
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

    public static String videoProcessLock(Long videoId) {
        return VIDEO_PROCESS_LOCK_PREFIX + videoId;
    }

    public static String resourcePurgeLock(Long videoId) {
        return RESOURCE_PURGE_LOCK_PREFIX + videoId;
    }
}
