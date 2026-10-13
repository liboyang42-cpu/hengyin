package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;

public class MerchantCrmNote {
    private Long id;
    @JsonIgnore private Long merchantId;
    @JsonIgnore private Long customerMemberId;
    @JsonIgnore private Long actorMemberId;
    @JsonIgnore private String requestId;
    private Long correctsNoteId;
    private String content;
    private Integer hidden;
    @JsonIgnore private Long deletedBy;
    private Date deletedAt;
    @JsonIgnore private String hiddenRequestId;
    private Integer version;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long value) { merchantId = value; }
    public Long getCustomerMemberId() { return customerMemberId; }
    public void setCustomerMemberId(Long value) { customerMemberId = value; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long value) { actorMemberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public Long getCorrectsNoteId() { return correctsNoteId; }
    public void setCorrectsNoteId(Long value) { correctsNoteId = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public Integer getHidden() { return hidden; }
    public void setHidden(Integer value) { hidden = value; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long value) { deletedBy = value; }
    public Date getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Date value) { deletedAt = value; }
    public String getHiddenRequestId() { return hiddenRequestId; }
    public void setHiddenRequestId(String value) { hiddenRequestId = value; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer value) { version = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
}
