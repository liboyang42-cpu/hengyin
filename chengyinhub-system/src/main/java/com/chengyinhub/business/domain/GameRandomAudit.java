package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 统一可审计随机流水 game_random_audit。
 */
public class GameRandomAudit {
    private Long id;
    private String domain;
    private String receiptId;
    private String seedDigest;
    private Integer ticket;
    private Integer total;
    private Long memberId;
    private String refType;
    private String refId;
    private String outcomeJson;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    public String getSeedDigest() { return seedDigest; }
    public void setSeedDigest(String seedDigest) { this.seedDigest = seedDigest; }
    public Integer getTicket() { return ticket; }
    public void setTicket(Integer ticket) { this.ticket = ticket; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getOutcomeJson() { return outcomeJson; }
    public void setOutcomeJson(String outcomeJson) { this.outcomeJson = outcomeJson; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
