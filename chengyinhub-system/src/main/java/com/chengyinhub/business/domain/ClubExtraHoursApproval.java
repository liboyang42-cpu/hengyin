package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * A2 额外工时预审批事件（append-only）。
 *
 * <p>更正只能新增 correctionOf 指向原事件的新行，禁止 UPDATE 原审批；
 * approvedAt 由数据库写（CURRENT_TIMESTAMP(3)），不接受 Java 传入，
 * 否则「事后补一条预审批」在时间上无法证伪。
 */
public class ClubExtraHoursApproval {
    private Long id;
    private Long topicId;
    private Long clubId;
    private String category;
    private BigDecimal approvedHours;
    private Long approvedBy;
    private Date approvedAt;
    private Date workStartedAt;
    private String idempotencyKey;
    private Long correctionOf;
    private String note;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public BigDecimal getApprovedHours() { return approvedHours; } public void setApprovedHours(BigDecimal v) { approvedHours = v; }
    public Long getApprovedBy() { return approvedBy; } public void setApprovedBy(Long v) { approvedBy = v; }
    public Date getApprovedAt() { return approvedAt; } public void setApprovedAt(Date v) { approvedAt = v; }
    public Date getWorkStartedAt() { return workStartedAt; } public void setWorkStartedAt(Date v) { workStartedAt = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public Long getCorrectionOf() { return correctionOf; } public void setCorrectionOf(Long v) { correctionOf = v; }
    public String getNote() { return note; } public void setNote(String v) { note = v; }
}
