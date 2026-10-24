package com.chengyinhub.business.domain.vo;

import java.math.BigDecimal;
import java.util.Date;

/** Reporter-only progress; internal respondent, handler and deduction details are omitted. */
public class CoopComplaintProgressVO {
    private Long id;
    private Long topicId;
    private String topicName;
    private Integer status;
    private BigDecimal advanceAmount;
    private String reason;
    private String handleNote;
    private Date createTime;
    private Date updateTime;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long value) { topicId = value; }
    public String getTopicName() { return topicName; }
    public void setTopicName(String value) { topicName = value; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer value) { status = value; }
    public BigDecimal getAdvanceAmount() { return advanceAmount; }
    public void setAdvanceAmount(BigDecimal value) { advanceAmount = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String value) { handleNote = value; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date value) { createTime = value; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date value) { updateTime = value; }
    public String getStatusText() {
        if (status == null) return "状态待确认";
        switch (status) {
            case 0: return "已受理";
            case 1: return "已先行垫付";
            case 2: return "处理中";
            case 3: return "已关闭";
            default: return "状态待确认";
        }
    }
}
