package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 完局报表的单类结果事实（只读、按 member-day 与来源种类聚合）。
 *
 * <p>每行代表「某会员在某自然日的某来源种类」：{@code factCount} 是该行内按来源幂等身份
 * 去重后的事实数（推荐点击用 idempotency_key，其余用来源行主键）。服务层只做
 * distinct member / 求和，不重新解释口径。
 */
public class OutcomeFactRow {
    private Long memberId;
    private Date statDate;
    private String sourceKind;
    private Date firstOccurredAt;
    private Date lastOccurredAt;
    private Long factCount;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public Date getStatDate() { return statDate; }
    public void setStatDate(Date statDate) { this.statDate = statDate; }
    public String getSourceKind() { return sourceKind; }
    public void setSourceKind(String sourceKind) { this.sourceKind = sourceKind; }
    public Date getFirstOccurredAt() { return firstOccurredAt; }
    public void setFirstOccurredAt(Date firstOccurredAt) { this.firstOccurredAt = firstOccurredAt; }
    public Date getLastOccurredAt() { return lastOccurredAt; }
    public void setLastOccurredAt(Date lastOccurredAt) { this.lastOccurredAt = lastOccurredAt; }
    public Long getFactCount() { return factCount; }
    public void setFactCount(Long factCount) { this.factCount = factCount; }
}
