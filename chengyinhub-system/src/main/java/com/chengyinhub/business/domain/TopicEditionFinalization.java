package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 期级 FINAL 结算头。一期一行；重放读回同一行，不重复产生应付。 */
public class TopicEditionFinalization {
    public static final String STATUS_FINAL = "FINAL";

    private Long id;
    private Long topicId;
    private Long clubId;
    private String finalStatus;
    private BigDecimal fixedCompensation;
    private BigDecimal qualityAmount;
    private BigDecimal extraAmount;
    /** 按已赚票数计酬的承接费（2026-08-25 换轨）。历史终结头是 0，不回填。 */
    private BigDecimal headcountAmount;
    private Long payeeProfileId;
    private Long payeeProfileVersion;
    private String payeeFactsHash;
    private String idempotencyKey;
    private Long finalizedBy;
    private Date finalizedAt;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public String getFinalStatus() { return finalStatus; } public void setFinalStatus(String v) { finalStatus = v; }
    public BigDecimal getFixedCompensation() { return fixedCompensation; } public void setFixedCompensation(BigDecimal v) { fixedCompensation = v; }
    public BigDecimal getQualityAmount() { return qualityAmount; } public void setQualityAmount(BigDecimal v) { qualityAmount = v; }
    public BigDecimal getExtraAmount() { return extraAmount; } public void setExtraAmount(BigDecimal v) { extraAmount = v; }
    public BigDecimal getHeadcountAmount() { return headcountAmount; } public void setHeadcountAmount(BigDecimal v) { headcountAmount = v; }
    public Long getPayeeProfileId() { return payeeProfileId; } public void setPayeeProfileId(Long v) { payeeProfileId = v; }
    public Long getPayeeProfileVersion() { return payeeProfileVersion; } public void setPayeeProfileVersion(Long v) { payeeProfileVersion = v; }
    public String getPayeeFactsHash() { return payeeFactsHash; } public void setPayeeFactsHash(String v) { payeeFactsHash = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public Long getFinalizedBy() { return finalizedBy; } public void setFinalizedBy(Long v) { finalizedBy = v; }
    public Date getFinalizedAt() { return finalizedAt; } public void setFinalizedAt(Date v) { finalizedAt = v; }
}
