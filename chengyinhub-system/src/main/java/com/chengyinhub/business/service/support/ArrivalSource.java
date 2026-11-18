package com.chengyinhub.business.service.support;

/**
 * F08 到达来源(player_node_progress.arrival_source)。排队计时 queue_joined_at 与店内负载只认 {@link #QR}:
 * 其余来源是玩家自己点的,不能当成「人在店门口扫了码」。
 */
public final class ArrivalSource {
    /** 扫店内静态码或一次性游戏码(/api/play/checkin)。 */
    public static final String QR = "QR";
    /** 自由探索:店铺 300 米内点「我已到店」,定位通过(/api/play/arrive)。 */
    public static final String GEO_TAP = "GEO_TAP";
    /** 城市定向:开局后逐站点「我已到达」。 */
    public static final String TAP = "TAP";
    /** 纯线上节点(无店、无坐标)。 */
    public static final String ONLINE = "ONLINE";

    private ArrivalSource() {
    }
}
