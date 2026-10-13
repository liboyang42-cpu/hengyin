package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/** 探店日期级资金快照；历史版本只追加，旧行只允许置为非 current。 */
public class TopicEditionFunds extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String KIND_PROVISIONAL = "PROVISIONAL";
    public static final String KIND_FINAL = "FINAL";

    private Long id;
    private Long topicId;
    private String snapshotKind;
    private Long version;
    private Integer current;
    private Long nCash;
    private BigDecimal gCash;
    private BigDecimal uIn;
    private BigDecimal gEarned;
    private BigDecimal uOut;
    private BigDecimal uPending;
    private BigDecimal rRefund;
    private String inputHash;
    private String idempotencyKey;
    private String computedBy;
    private Date computedAt;
    private String supersededBy;
    private Date supersededAt;
    private String supersedeReason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public String getSnapshotKind() { return snapshotKind; }
    public void setSnapshotKind(String snapshotKind) { this.snapshotKind = snapshotKind; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Integer getCurrent() { return current; }
    public void setCurrent(Integer current) { this.current = current; }
    public Long getNCash() { return nCash; }
    public void setNCash(Long nCash) { this.nCash = nCash; }
    public BigDecimal getGCash() { return gCash; }
    public void setGCash(BigDecimal gCash) { this.gCash = gCash; }
    public BigDecimal getUIn() { return uIn; }
    public void setUIn(BigDecimal uIn) { this.uIn = uIn; }
    public BigDecimal getGEarned() { return gEarned; }
    public void setGEarned(BigDecimal gEarned) { this.gEarned = gEarned; }
    public BigDecimal getUOut() { return uOut; }
    public void setUOut(BigDecimal uOut) { this.uOut = uOut; }
    public BigDecimal getUPending() { return uPending; }
    public void setUPending(BigDecimal uPending) { this.uPending = uPending; }
    public BigDecimal getRRefund() { return rRefund; }
    public void setRRefund(BigDecimal rRefund) { this.rRefund = rRefund; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String inputHash) { this.inputHash = inputHash; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getComputedBy() { return computedBy; }
    public void setComputedBy(String computedBy) { this.computedBy = computedBy; }
    public Date getComputedAt() { return computedAt; }
    public void setComputedAt(Date computedAt) { this.computedAt = computedAt; }
    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String supersededBy) { this.supersededBy = supersededBy; }
    public Date getSupersededAt() { return supersededAt; }
    public void setSupersededAt(Date supersededAt) { this.supersededAt = supersededAt; }
    public String getSupersedeReason() { return supersedeReason; }
    public void setSupersedeReason(String supersedeReason) { this.supersedeReason = supersedeReason; }
}
