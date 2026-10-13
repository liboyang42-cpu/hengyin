package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * A1 质量奖励逐维决定。每维只能 0 或 200，没有中间值；T+7 前是 PENDING，不是默认满额。
 *
 * <p>版本化：更正写新版本并把旧版本置为非 current，一次确认由 CAS 保证。
 */
public class ClubQualityDecision {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private Long id;
    private Long topicId;
    private Long clubId;
    private String dimension;
    private String decisionStatus;
    private BigDecimal amount;
    private String evidenceHash;
    private Integer excludedByFault;
    /**
     * 归责方（MERCHANT / PLATFORM）。★必须<b>落库</b>：本仓 {@code LogAspect} 已加固到不写请求体，
     * 「随 @Log 落 sys_oper_log」是假的。「哪一方的过错让这一维走了排除路径」正是排除动作唯一
     * 需要留痕的事实，丢了就没地方可查。
     */
    private String faultParty;
    /**
     * 本次判定 / 排除的理由。一次确认与过错排除<b>都必填</b>并落库。
     *
     * <p>不指望 {@code @Log}：本仓 LogAspect 已加固到不写请求体（{@code setOperParam} /
     * {@code setJsonResult} 全仓 0 命中），{@code sys_oper_log} 只有 title/method/url/操作人/耗时。
     * 开售种子那三行是 PENDING，此列为空；DB 的 {@code ck_club_quality_reason} 只约束已决行。
     */
    private String reason;
    private Date dueAt;
    private Long confirmedBy;
    private Date confirmedAt;
    private Long version;
    private Integer current;
    private Long correctionOf;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public String getDimension() { return dimension; } public void setDimension(String v) { dimension = v; }
    public String getDecisionStatus() { return decisionStatus; } public void setDecisionStatus(String v) { decisionStatus = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getEvidenceHash() { return evidenceHash; } public void setEvidenceHash(String v) { evidenceHash = v; }
    /** 1 = 已证明由商家或平台造成的过错，该维排除计酬。 */
    public Integer getExcludedByFault() { return excludedByFault; } public void setExcludedByFault(Integer v) { excludedByFault = v; }
    public String getFaultParty() { return faultParty; } public void setFaultParty(String v) { faultParty = v; }
    public String getReason() { return reason; } public void setReason(String v) { reason = v; }
    public Date getDueAt() { return dueAt; } public void setDueAt(Date v) { dueAt = v; }
    public Long getConfirmedBy() { return confirmedBy; } public void setConfirmedBy(Long v) { confirmedBy = v; }
    public Date getConfirmedAt() { return confirmedAt; } public void setConfirmedAt(Date v) { confirmedAt = v; }
    public Long getVersion() { return version; } public void setVersion(Long v) { version = v; }
    public Integer getCurrent() { return current; } public void setCurrent(Integer v) { current = v; }
    public Long getCorrectionOf() { return correctionOf; } public void setCorrectionOf(Long v) { correctionOf = v; }
}
