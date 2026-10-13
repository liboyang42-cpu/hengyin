package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 俱乐部每期四类工时的计划、自报和平台确认事实。 */
public class ClubEditionHours {
    private Long id;
    private Long topicId;
    private Long clubId;
    private String hourKind;
    private BigDecimal plannedHours;
    private BigDecimal reportedActualHours;
    private BigDecimal confirmedActualHours;
    private String confirmStatus;
    private Long reportedBy;
    private Date reportedAt;
    private Long confirmedBy;
    private Date confirmedAt;
    private String evidenceNote;
    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getTopicId() { return topicId; } public void setTopicId(Long v) { topicId = v; }
    public Long getClubId() { return clubId; } public void setClubId(Long v) { clubId = v; }
    public String getHourKind() { return hourKind; } public void setHourKind(String v) { hourKind = v; }
    public BigDecimal getPlannedHours() { return plannedHours; } public void setPlannedHours(BigDecimal v) { plannedHours = v; }
    public BigDecimal getReportedActualHours() { return reportedActualHours; } public void setReportedActualHours(BigDecimal v) { reportedActualHours = v; }
    public BigDecimal getConfirmedActualHours() { return confirmedActualHours; } public void setConfirmedActualHours(BigDecimal v) { confirmedActualHours = v; }
    public String getConfirmStatus() { return confirmStatus; } public void setConfirmStatus(String v) { confirmStatus = v; }
    public Long getReportedBy() { return reportedBy; } public void setReportedBy(Long v) { reportedBy = v; }
    public Date getReportedAt() { return reportedAt; } public void setReportedAt(Date v) { reportedAt = v; }
    public Long getConfirmedBy() { return confirmedBy; } public void setConfirmedBy(Long v) { confirmedBy = v; }
    public Date getConfirmedAt() { return confirmedAt; } public void setConfirmedAt(Date v) { confirmedAt = v; }
    public String getEvidenceNote() { return evidenceNote; } public void setEvidenceNote(String v) { evidenceNote = v; }
}
