package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * P3 统一审核记录对象 audit_record。
 */
public class AuditRecord extends BaseEntity {
    private static final long serialVersionUID = 1L;

    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_REVERT = "REVERT";
    public static final String ACTION_SUBMIT = "SUBMIT";
    public static final String ACTION_REPORT = "REPORT";
    public static final String REASON_NOT_RECORDED = "NOT_RECORDED";
    public static final String REASON_RECORDED_EMPTY = "RECORDED_EMPTY";
    public static final String REASON_RECORDED = "RECORDED";

    private Long id;
    private Long taskId;
    private String action;
    private Long operatorId;
    private String reasonCode;
    private String reasonText;
    private String snapshotJson;
    private Long revertedRecordId;
    /** 固定清单原因编码(JSON 数组)，见 AuditReasonCodes；对外只暴露 reasonCodes / reasonLabels。 */
    private String reasonCodesJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Long getRevertedRecordId() { return revertedRecordId; }
    public void setRevertedRecordId(Long revertedRecordId) { this.revertedRecordId = revertedRecordId; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getReasonCodesJson() { return reasonCodesJson; }
    public void setReasonCodesJson(String reasonCodesJson) { this.reasonCodesJson = reasonCodesJson; }
    public java.util.List<String> getReasonCodes() {
        return com.chengyinhub.business.service.support.AuditReasonCodes.fromJson(reasonCodesJson);
    }
    public java.util.List<String> getReasonLabels() {
        return com.chengyinhub.business.service.support.AuditReasonCodes.labels(getReasonCodes());
    }

    /** NF-01 对外契约别名；历史 null 不补造文案，空串也不折叠成 null。 */
    public String getReason() { return reasonText; }
    public Long getDecidedBy() { return operatorId; }
    public java.util.Date getDecidedAt() { return getCreateTime(); }
    public String getReasonState() {
        if (reasonText == null) return REASON_NOT_RECORDED;
        if (reasonText.isEmpty()) return REASON_RECORDED_EMPTY;
        return REASON_RECORDED;
    }
}
