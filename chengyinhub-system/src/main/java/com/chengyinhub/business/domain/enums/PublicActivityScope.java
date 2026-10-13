package com.chengyinhub.business.domain.enums;

/**
 * 公开活动列表的可见性范围 —— 每个调用方必须显式表态，不给默认值。
 *
 * <p>为什么要有这个枚举:{@code selectPublicCmsActivityList} 名字里带 public,但它的闸只硬编码了一半。
 * status / publish_status / del_flag 写死在 SQL 里,而「不出社群活动」和「只出未结束的」是两个
 * <b>可选的 &lt;if&gt; 开关</b>,新调用方默认零防护、要靠人记得拨。四个调用方于是拼出了四套不同的闸:
 *
 * <pre>
 *   activityList is_my=0  →  两道都开     ✅
 *   activityList is_my=2  →  只开了社群闸  ❌ 漏「已结束」
 *   likeList              →  两道都没开    ❌
 *   ApiTopicController    →  两道都没开    ❌ 已结束的场次被列成「可参加」
 * </pre>
 *
 * <p>2026-08-27 生产实测:8 条 3 月就结束的活动确实命中最后一条 —— 主题详情页把它们当可参加场次下发。
 * 补一句 {@code setIsNew(1)} 只能修一个入口,另外三个照漏,下一个调用方继续从零开始漏。
 * 所以把这两个维度收进枚举、放进方法签名:<b>忘不掉,因为编译器不让你忘。</b>
 *
 * <p>「已取消」不在这里 —— 它没有例外,任何公开面都不该出已取消的活动,已直接写死进 SQL。
 */
public enum PublicActivityScope {

    /**
     * 公域货架:首页附近活动 / 首页活动页签 / 即将上线 / 搜索 / 地图。
     * 不出社群活动(M1-3:俱乐部活动仅俱乐部成员经俱乐部入口可见),且只出未结束的。
     */
    PUBLIC_SHELF(true, true),

    /**
     * 主题详情页的「可参加场次」。
     * 出社群活动(俱乐部承接的场次正是要在主题下被看到),但只出未结束的 —— 已结束的不叫可参加。
     */
    TOPIC_SHELF(false, true),

    /**
     * 会员收藏列表。
     * 出社群活动,且<b>保留已结束的</b> —— 用户收藏过的历史活动仍应看得见,这是这里唯一
     * 允许出现已结束活动的地方。
     */
    MEMBER_FAVOURITES(false, false);

    private final boolean excludeClubActivities;
    private final boolean liveOnly;

    PublicActivityScope(boolean excludeClubActivities, boolean liveOnly) {
        this.excludeClubActivities = excludeClubActivities;
        this.liveOnly = liveOnly;
    }

    /** true = 只出公域活动(club_id 为空);false = 社群活动也出。 */
    public boolean excludesClubActivities() {
        return excludeClubActivities;
    }

    /** true = 只出 end_date 还没到的;false = 已结束的也出。 */
    public boolean isLiveOnly() {
        return liveOnly;
    }
}
