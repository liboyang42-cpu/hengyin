package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 开售时刻冻结的补偿条款：F 固定补偿、EXTRA 单价、Q 每维金额与证据截止、适用执行俱乐部。
 *
 * <p>写入后不可变；FINAL 只读它，绝不用「最新配置」覆盖历史。
 */
public class ClubEditionCompensationTerms {
    /** A1 三个固定维度，顺序即 canonical 顺序，不得增删改。 */
    public static final String[] QUALITY_DIMENSIONS =
            {"DELIVERY_SAFETY", "PLAYER_EXPERIENCE", "CONTENT_REPORT"};
    /** A2 四类工时，与 club_edition_hours.hour_kind 的 CHECK 同集合。 */
    public static final String[] HOUR_KINDS = {"PREP", "CONTENT", "ONSITE", "REVIEW"};

    private Long id;
    private Long topicId;
    private Long executingClubId;
    private BigDecimal fixedCompensation;
    private BigDecimal extraHourRate;
    private BigDecimal qualityDimensionAmount;
    private String qualityDimensions;
    private Date evidenceDueAt;
    /**
     * 本期履约窗结束的<b>权威</b>瞬间（{@code FulfillmentWindowResolver} 的 endExclusive），开售冻结。
     *
     * <p>两处消费：①EXTRA 事前审批的 workStartedAt 上界（批的活不能落在本期之外）；
     * ②FINAL 的「期已结束」事实门。冻在这里而不是每次回算 topic，是因为 topic 的日期在开售后仍可被改。
     */
    private Date fulfillmentEndAt;
    /**
     * 本期额外工时的<b>审批人</b>（总纲 §2.1.2：「超范围劳动的小时单价<b>和审批人</b>必须在开售快照中冻结」）。
     *
     * <p>取值是平台 ORGANIZER 产品角色的 {@code declared_by} —— 开售闸已经强制它非空且 &gt; 0
     * （{@code SALE_FREE_EXPLORE_ORGANIZER_AUDIT_MISSING}），是仓库里现成、可审计、开售当场就存在的
     * 「这一期平台侧负责人」。不另造一个身份字段，也不拿「谁持 business:clubextra:approve」当审批人 ——
     * 那个分派开售后还能改，冻不住。
     */
    private Long extraApproverUserId;
    /**
     * HEADCOUNT 每已赚票单价（2026-08-25 承接费换轨）。开售冻结。
     *
     * <p>为什么按「已赚票数」而不是工时：工时是<b>投入</b>且是自陈数字，平台没有任何能证伪它的
     * 服务端事实；已赚票数是<b>产出</b>且是平台侧事实 —— 口径就是
     * {@code gEarned = gCash + uIn − uOut − rRefund} 那条链上的
     * {@code topic_edition_funds_attribution.ticket_count}，已扣退款、FINAL 同事务固化。
     *
     * <p>0 是合法配置：这一期不按人头计酬，只发保底 F。
     */
    private BigDecimal headcountRate;
    /** HEADCOUNT 本期封顶。{@code null} = 不封顶（平台侧敞口无上界，配的时候心里要有数）。 */
    private BigDecimal headcountCap;
    private String configHash;
    private Date frozenAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getExecutingClubId() { return executingClubId; } public void setExecutingClubId(Long v) { executingClubId = v; }
    public BigDecimal getFixedCompensation() { return fixedCompensation; } public void setFixedCompensation(BigDecimal v) { fixedCompensation = v; }
    public BigDecimal getExtraHourRate() { return extraHourRate; } public void setExtraHourRate(BigDecimal v) { extraHourRate = v; }
    public BigDecimal getQualityDimensionAmount() { return qualityDimensionAmount; } public void setQualityDimensionAmount(BigDecimal v) { qualityDimensionAmount = v; }
    public String getQualityDimensions() { return qualityDimensions; } public void setQualityDimensions(String v) { qualityDimensions = v; }
    public Date getEvidenceDueAt() { return evidenceDueAt; } public void setEvidenceDueAt(Date v) { evidenceDueAt = v; }
    public Date getFulfillmentEndAt() { return fulfillmentEndAt; } public void setFulfillmentEndAt(Date v) { fulfillmentEndAt = v; }
    public Long getExtraApproverUserId() { return extraApproverUserId; } public void setExtraApproverUserId(Long v) { extraApproverUserId = v; }
    public BigDecimal getHeadcountRate() { return headcountRate; } public void setHeadcountRate(BigDecimal v) { headcountRate = v; }
    public BigDecimal getHeadcountCap() { return headcountCap; } public void setHeadcountCap(BigDecimal v) { headcountCap = v; }
    public String getConfigHash() { return configHash; } public void setConfigHash(String v) { configHash = v; }
    public Date getFrozenAt() { return frozenAt; } public void setFrozenAt(Date v) { frozenAt = v; }
}
