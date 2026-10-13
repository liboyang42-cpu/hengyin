package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * coop 客诉工单(责任链)。有未结工单(status<3)时冻结该主题结算放款。
 */
public class CoopComplaint extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final int STATUS_ACCEPTED = 0; // 受理
    public static final int STATUS_ADVANCED = 1; // 已先行垫付
    public static final int STATUS_RECOVERING = 2; // 追偿中
    public static final int STATUS_CLOSED = 3; // 关闭(解冻)

    private Long id;
    private Long topicId;
    private Long registrationId;
    private Long reporterMemberId;
    private String respondentType; // initiator/club/merchant
    private Long respondentMemberId;
    private Integer status;
    private BigDecimal advanceAmount;
    private Integer faultRatio;
    private BigDecimal deductAmount;
    private String reason;
    private String handleNote;
    private Long handlerId;
    private Integer delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getReporterMemberId() { return reporterMemberId; }
    public void setReporterMemberId(Long reporterMemberId) { this.reporterMemberId = reporterMemberId; }
    public String getRespondentType() { return respondentType; }
    public void setRespondentType(String respondentType) { this.respondentType = respondentType; }
    public Long getRespondentMemberId() { return respondentMemberId; }
    public void setRespondentMemberId(Long respondentMemberId) { this.respondentMemberId = respondentMemberId; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public BigDecimal getAdvanceAmount() { return advanceAmount; }
    public void setAdvanceAmount(BigDecimal advanceAmount) { this.advanceAmount = advanceAmount; }
    public Integer getFaultRatio() { return faultRatio; }
    public void setFaultRatio(Integer faultRatio) { this.faultRatio = faultRatio; }
    public BigDecimal getDeductAmount() { return deductAmount; }
    public void setDeductAmount(BigDecimal deductAmount) { this.deductAmount = deductAmount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public Long getHandlerId() { return handlerId; }
    public void setHandlerId(Long handlerId) { this.handlerId = handlerId; }
    public Integer getDelFlag() { return delFlag; }
    public void setDelFlag(Integer delFlag) { this.delFlag = delFlag; }
}
