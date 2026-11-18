package com.chengyinhub.business.service.support;

/**
 * 自由探索「到访」(FE-STORY-1117 第四、五节):到访 = 该地点 GPS 校验通过(默认 300 米,可配 300–500 米),
 * 或在配置为扫码的地点扫码成功;纯线上节点开票即算到访。只解锁剧情,不扣权益,不需要店员。
 *
 * <p>节点配置 cms_topic_node.visit_check(0 GPS 默认 / 1 扫码 / 2 纯线上);进度事实
 * player_node_progress.visit_method(1 GPS / 2 扫码 / 3 线上 / 4 队友共享)与 visit_time(只写第一次)。
 * 城市定向(mode 1)不读不写这些列。
 */
public final class FreeExploreVisit {
    public static final int CHECK_GPS = 0;
    public static final int CHECK_SCAN = 1;
    public static final int CHECK_ONLINE = 2;

    public static final int METHOD_GPS = 1;
    public static final int METHOD_SCAN = 2;
    public static final int METHOD_ONLINE = 3;
    public static final int METHOD_TEAM = 4;

    private FreeExploreVisit() {
    }

    /** 缺配置/非法值一律按默认 GPS(和迁移列默认值一致)。 */
    public static int normalizeCheck(Integer visitCheck) {
        if (visitCheck == null) return CHECK_GPS;
        int v = visitCheck.intValue();
        return v == CHECK_SCAN || v == CHECK_ONLINE ? v : CHECK_GPS;
    }

    /** /api/play/nodes 下发给端上的到访方式文本。 */
    public static String checkLabel(Integer visitCheck) {
        switch (normalizeCheck(visitCheck)) {
            case CHECK_SCAN: return "SCAN";
            case CHECK_ONLINE: return "ONLINE";
            default: return "GPS";
        }
    }
}
