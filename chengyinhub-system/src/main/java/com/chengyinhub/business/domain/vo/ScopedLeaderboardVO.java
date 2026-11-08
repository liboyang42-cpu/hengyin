package com.chengyinhub.business.domain.vo;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * BE-14 多作用域榜单只读投影：{scope, scopeId, period, entries:[{rank,memberId,nickname,avatar,score}], me}。
 *
 * <p>不含邮箱、手机号、精确位置；写侧仍是完成事实与回执，本投影永不写分数。
 */
public class ScopedLeaderboardVO {
    /** world / country / city / quest / club */
    private String scope;
    private String scopeId;
    /** week / all */
    private String period;
    /** 周榜窗口起点（含）；累计榜为 null。 */
    private Date periodStart;
    /** 周榜窗口终点（不含）；累计榜为 null。 */
    private Date periodEnd;
    /** 分数口径标识，当前只有 COMPLETED_NODES。 */
    private String scoreBasis;
    private List<ScopedLeaderboardEntryVO> entries = Collections.emptyList();
    /** 当前登录用户；无有效对局时 score=0 且 rank=null。 */
    private ScopedLeaderboardEntryVO me;
    private ScopedLeaderboardCoverageVO coverage;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Date getPeriodStart() { return periodStart; }
    public void setPeriodStart(Date periodStart) { this.periodStart = periodStart; }
    public Date getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Date periodEnd) { this.periodEnd = periodEnd; }
    public String getScoreBasis() { return scoreBasis; }
    public void setScoreBasis(String scoreBasis) { this.scoreBasis = scoreBasis; }
    public List<ScopedLeaderboardEntryVO> getEntries() { return entries; }
    public void setEntries(List<ScopedLeaderboardEntryVO> entries) { this.entries = entries; }
    public ScopedLeaderboardEntryVO getMe() { return me; }
    public void setMe(ScopedLeaderboardEntryVO me) { this.me = me; }
    public ScopedLeaderboardCoverageVO getCoverage() { return coverage; }
    public void setCoverage(ScopedLeaderboardCoverageVO coverage) { this.coverage = coverage; }
}
