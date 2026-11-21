package com.chengyinhub.business.growthloops;

/**
 * GRO-08：CITY 领地事件通知的调用口（给协调者接线用；本包不改 citygame 代码）。
 *
 * <p>全部方法：不抛异常、不阻塞；在调用方事务<b>提交之后</b>才推送（事务回滚则不推）。推送走 links-push 的 APNs 扇出，
 * 遵守账号默认的“通知关闭 / 免打扰时段”；同一 (会员, thread) 30 秒内只推一条。APNs 未配置时什么也不做。</p>
 *
 * <p>接线示例（在占领/挑战结算成功后，同一事务内）：</p>
 * <pre>
 * &#64;Autowired(required = false) private ICityEventNotifyService cityNotify;
 * ...
 * if (cityNotify != null) cityNotify.challenged(holderId, cellKey, placeName);
 * if (cityNotify != null) cityNotify.lost(previousHolderId, cellKey, placeName);
 * // 每周产出结算任务里，每位领主一条：
 * if (cityNotify != null) cityNotify.weeklyYield(holderId, weekKey, placesHeld, coinAmount);
 * </pre>
 * 点击都打开 App 的漫游页（小程序源路径 {@code /pages/roam/index}，App 内为领地视图所在的页）。
 */
public interface ICityEventNotifyService
{
    /** 你的据点正被挑战。placeRef 是 CITY 的格子/节点键（用于合并同一据点的多条），placeName 可为空。 */
    void challenged(long holderMemberId, String placeRef, String placeName);

    /** 你失去了一个据点。 */
    void lost(long formerHolderMemberId, String placeRef, String placeName);

    /**
     * 每周产出到账。weekKey 例如 "2026-W46"（同一周重复调用只推一次）；coins 为城市币数量（不可兑现），
     * 为 null 时只说据点数。
     */
    void weeklyYield(long holderMemberId, String weekKey, int placesHeld, Long coins);

    // ------------------------------------------------------------ W7 CITY-11: City Monopoly events (default: no-op)

    /**
     * City Monopoly takeover: the former holder lost the place. revengeHours/revengeRate describe the cheaper strike
     * back (challenge.revenge_discount.*); revengeRate is the share taken off the fee (0.5 = half).
     */
    default void monopolyLost(long formerHolderMemberId, String placeRef, String placeName, long revengeHours, double revengeRate) {}

    /** The holder defended: the challenger rolled {@code total}, short of {@code dc}. */
    default void defended(long holderMemberId, String placeRef, String placeName, int total, int dc) {}

    /** Nobody has visited the place for {@code days} days; it starts losing levels soon. */
    default void decaying(long holderMemberId, String placeRef, String placeName, long days) {}

    /** The place was released because it was left alone too long. */
    default void released(long formerHolderMemberId, String placeRef, String placeName) {}

    /** Season result: rank among {@code participants}; {@code xp} may be 0. */
    default void seasonResult(long memberId, String seasonId, int rank, int participants, int xp) {}
}
