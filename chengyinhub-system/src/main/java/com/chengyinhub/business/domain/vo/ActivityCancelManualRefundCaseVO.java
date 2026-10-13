package com.chengyinhub.business.domain.vo;

import java.util.Date;
import java.util.List;

/** 经服务端完整性校验后提供给退款后台的活动取消人工评估 case。 */
public class ActivityCancelManualRefundCaseVO {
    public static final String TYPE_MIXED_TICKETS = "MIXED_TICKETS";
    public static final String TYPE_PARTIAL_CHAPTERS = "PARTIAL_CHAPTERS";
    private String caseType = TYPE_MIXED_TICKETS;
    private String chapterSnapshotJson;

    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public String getChapterSnapshotJson() { return chapterSnapshotJson; }
    public void setChapterSnapshotJson(String chapterSnapshotJson) { this.chapterSnapshotJson = chapterSnapshotJson; }

    private Long id;
    private Long clubId;
    private Long activityId;
    /** FX-05:主题级票工单挂这里;与 activityId 恰好一个非空(库上 CHECK 强制)。 */
    private Long topicId;
    private Long ownerMemberId;
    private Long actorMemberId;
    private String parentOrderNo;
    private Long payerRegistrationId;
    private Long payerMemberId;
    private Long paidCashAmountFen;
    private List<Long> verifiedRegistrationIds;
    private List<Long> unverifiedRegistrationIds;
    private String status;
    private Long assessedRefundAmountFen;
    private String assessmentNote;
    private Long assessedByUserId;
    private String assessmentRequestId;
    private Date assessedAt;
    private Long actualRefundAmountFen;
    private String manualChannel;
    private String evidenceSha256;
    private String evidencePreviewUrl;
    private String handleNote;
    private Long handledByUserId;
    private String handleRequestId;
    private Date handledAt;
    private String verificationNote;
    private Long verifiedByUserId;
    private String verifyRequestId;
    private Date verifiedAt;
    private String evidenceVerificationNotice;
    private Long version;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public Long getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(Long ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public Long getActorMemberId() { return actorMemberId; }
    public void setActorMemberId(Long actorMemberId) { this.actorMemberId = actorMemberId; }
    public String getParentOrderNo() { return parentOrderNo; }
    public void setParentOrderNo(String parentOrderNo) { this.parentOrderNo = parentOrderNo; }
    public Long getPayerRegistrationId() { return payerRegistrationId; }
    public void setPayerRegistrationId(Long payerRegistrationId) { this.payerRegistrationId = payerRegistrationId; }
    public Long getPayerMemberId() { return payerMemberId; }
    public void setPayerMemberId(Long payerMemberId) { this.payerMemberId = payerMemberId; }
    public Long getPaidCashAmountFen() { return paidCashAmountFen; }
    public void setPaidCashAmountFen(Long paidCashAmountFen) { this.paidCashAmountFen = paidCashAmountFen; }
    public List<Long> getVerifiedRegistrationIds() { return verifiedRegistrationIds; }
    public void setVerifiedRegistrationIds(List<Long> verifiedRegistrationIds) { this.verifiedRegistrationIds = verifiedRegistrationIds; }
    public List<Long> getUnverifiedRegistrationIds() { return unverifiedRegistrationIds; }
    public void setUnverifiedRegistrationIds(List<Long> unverifiedRegistrationIds) { this.unverifiedRegistrationIds = unverifiedRegistrationIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAssessedRefundAmountFen() { return assessedRefundAmountFen; }
    public void setAssessedRefundAmountFen(Long assessedRefundAmountFen) { this.assessedRefundAmountFen = assessedRefundAmountFen; }
    public String getAssessmentNote() { return assessmentNote; }
    public void setAssessmentNote(String assessmentNote) { this.assessmentNote = assessmentNote; }
    public Long getAssessedByUserId() { return assessedByUserId; }
    public void setAssessedByUserId(Long assessedByUserId) { this.assessedByUserId = assessedByUserId; }
    public String getAssessmentRequestId() { return assessmentRequestId; }
    public void setAssessmentRequestId(String assessmentRequestId) { this.assessmentRequestId = assessmentRequestId; }
    public Date getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Date assessedAt) { this.assessedAt = assessedAt; }
    public Long getActualRefundAmountFen() { return actualRefundAmountFen; }
    public void setActualRefundAmountFen(Long actualRefundAmountFen) { this.actualRefundAmountFen = actualRefundAmountFen; }
    public String getManualChannel() { return manualChannel; }
    public void setManualChannel(String manualChannel) { this.manualChannel = manualChannel; }
    public String getEvidenceSha256() { return evidenceSha256; }
    public void setEvidenceSha256(String evidenceSha256) { this.evidenceSha256 = evidenceSha256; }
    public String getEvidencePreviewUrl() { return evidencePreviewUrl; }
    public void setEvidencePreviewUrl(String evidencePreviewUrl) { this.evidencePreviewUrl = evidencePreviewUrl; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public Long getHandledByUserId() { return handledByUserId; }
    public void setHandledByUserId(Long handledByUserId) { this.handledByUserId = handledByUserId; }
    public String getHandleRequestId() { return handleRequestId; }
    public void setHandleRequestId(String handleRequestId) { this.handleRequestId = handleRequestId; }
    public Date getHandledAt() { return handledAt; }
    public void setHandledAt(Date handledAt) { this.handledAt = handledAt; }
    public String getVerificationNote() { return verificationNote; }
    public void setVerificationNote(String verificationNote) { this.verificationNote = verificationNote; }
    public Long getVerifiedByUserId() { return verifiedByUserId; }
    public void setVerifiedByUserId(Long verifiedByUserId) { this.verifiedByUserId = verifiedByUserId; }
    public String getVerifyRequestId() { return verifyRequestId; }
    public void setVerifyRequestId(String verifyRequestId) { this.verifyRequestId = verifyRequestId; }
    public Date getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Date verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getEvidenceVerificationNotice() { return evidenceVerificationNotice; }
    public void setEvidenceVerificationNotice(String evidenceVerificationNotice) { this.evidenceVerificationNotice = evidenceVerificationNotice; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
