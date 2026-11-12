package com.chengyinhub.business.domain.vo;

/**
 * BE-14 覆盖面说明：榜单只排名它能归因的局，其余如实计数，不补 0、不猜城市、不猜国家。
 */
public class ScopedLeaderboardCoverageVO {
    /** 该作用域内进入排名的人数。 */
    private Long rankedMembers;
    /**
     * 有完成节点记录、但没有「当前有效的已支付报名/通行证」的对局组数
     * （未支付、取消、过期、已退款、以及无法归因到任何订单的旧局都落在这里），不进入排名。
     */
    private Long unverifiedRuns;
    /**
     * city/country 专用：报名有效、但该局**没有冻结地理归属**（UNKNOWN）的对局组数。
     * 这类局按「旧局不取现可编辑值补历史」的规矩排除在 city/country 榜外；其它作用域为 null。
     */
    private Long unattributedRuns;
    /**
     * 地理归因依据，明示这份榜单按什么认领地理：
     * <ul>
     *   <li>{@code SCOPE_CONTENT}：world/quest/club，不涉及地理归因；</li>
     *   <li>{@code FROZEN_RUN_SNAPSHOT}：city/country，按该局冻结快照
     *       {@code topic_route_session.config_snapshot_json} 的
     *       {@code themeConfig.contentGeography.{cityCode,countryCode}} 归因；
     *       没有冻结值的旧局一律 UNKNOWN，不取 {@code cms_topic.city_code} 等当前可编辑值补历史。</li>
     * </ul>
     */
    private String attributionBasis;

    public Long getRankedMembers() { return rankedMembers; }
    public void setRankedMembers(Long rankedMembers) { this.rankedMembers = rankedMembers; }
    public Long getUnverifiedRuns() { return unverifiedRuns; }
    public void setUnverifiedRuns(Long unverifiedRuns) { this.unverifiedRuns = unverifiedRuns; }
    public Long getUnattributedRuns() { return unattributedRuns; }
    public void setUnattributedRuns(Long unattributedRuns) { this.unattributedRuns = unattributedRuns; }
    public String getAttributionBasis() { return attributionBasis; }
    public void setAttributionBasis(String attributionBasis) { this.attributionBasis = attributionBasis; }
}
