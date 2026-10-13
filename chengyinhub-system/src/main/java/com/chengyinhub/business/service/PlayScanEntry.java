package com.chengyinhub.business.service;

/** 玩家扫门口游戏码之后要去哪。 */
public final class PlayScanEntry {

    public static final String ACTION_PURCHASE = "purchase";
    public static final String ACTION_PLAY = "play";

    public final String action;
    public final Long topicId;
    public final Long nodeId;
    public final Long activityId;

    public PlayScanEntry(String action, Long topicId, Long nodeId, Long activityId) {
        this.action = action;
        this.topicId = topicId;
        this.nodeId = nodeId;
        this.activityId = activityId;
    }

    public static PlayScanEntry purchase(Long topicId, Long nodeId) {
        return new PlayScanEntry(ACTION_PURCHASE, topicId, nodeId, null);
    }

    public static PlayScanEntry play(Long topicId, Long nodeId, Long activityId) {
        return new PlayScanEntry(ACTION_PLAY, topicId, nodeId, activityId);
    }
}
