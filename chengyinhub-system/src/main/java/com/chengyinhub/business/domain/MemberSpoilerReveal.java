package com.chengyinhub.business.domain;

import java.util.Date;

public class MemberSpoilerReveal {
    private Long id;
    private Long memberId;
    private String contentType;
    private Long contentId;
    private String requestId;
    private Date revealedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getContentId() { return contentId; }
    public void setContentId(Long contentId) { this.contentId = contentId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Date getRevealedAt() { return revealedAt; }
    public void setRevealedAt(Date revealedAt) { this.revealedAt = revealedAt; }
}
