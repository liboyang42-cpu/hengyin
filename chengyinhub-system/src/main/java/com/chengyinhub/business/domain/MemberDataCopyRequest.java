package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * ONB-10 (PIPL Art. 45): a member's request for a copy of their personal information, handled as an admin ticket.
 * Table {@code member_data_copy_request}. Status: PENDING → PROCESSING → DELIVERED, or → REJECTED.
 */
public class MemberDataCopyRequest {
    private Long id;
    private Long memberId;
    private String status;
    private String note;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updatedAt;
    private Long handledBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date handledAt;
    /** Read-only for the admin list: the response deadline (created date + configured working days). */
    private String dueBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
    public Long getHandledBy() { return handledBy; }
    public void setHandledBy(Long handledBy) { this.handledBy = handledBy; }
    public Date getHandledAt() { return handledAt; }
    public void setHandledAt(Date handledAt) { this.handledAt = handledAt; }
    public String getDueBy() { return dueBy; }
    public void setDueBy(String dueBy) { this.dueBy = dueBy; }
}
