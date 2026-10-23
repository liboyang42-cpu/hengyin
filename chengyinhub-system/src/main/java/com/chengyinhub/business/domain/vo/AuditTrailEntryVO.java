package com.chengyinhub.business.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

/**
 * DOC2 O5c-(a):统一留痕读投影的一行。只读,来自五张既有留痕表之一,不新建表、不迁移数据。
 *
 * <p>source 取值:AUDIT_RECORD / BUSINESS_AUDIT_EVENT / POST_ENFORCEMENT / REPORT_TIMELINE /
 * OFFICIAL_EVENT_AUDIT_LOG。快照/before/after JSON 与举报人身份一律不投影。
 */
public class AuditTrailEntryVO {

    public static final String SOURCE_AUDIT_RECORD = "AUDIT_RECORD";
    public static final String SOURCE_BUSINESS_AUDIT_EVENT = "BUSINESS_AUDIT_EVENT";
    public static final String SOURCE_POST_ENFORCEMENT = "POST_ENFORCEMENT";
    public static final String SOURCE_REPORT_TIMELINE = "REPORT_TIMELINE";
    public static final String SOURCE_OFFICIAL_EVENT_AUDIT_LOG = "OFFICIAL_EVENT_AUDIT_LOG";

    private String source;
    /** 源表主键。 */
    private Long sourceId;
    /** 关联的审核单 / 举报单 id(源表自带的那一个;没有为 null)。 */
    private Long refId;
    private String action;
    /** 操作人类型:SYS_USER / MEMBER / PLATFORM / SYSTEM / REPORTER / SUBJECT;源表不区分时为 null。 */
    private String operatorType;
    private Long operatorId;
    private String reasonCode;
    private String reasonText;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date eventTime;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOperatorType() { return operatorType; }
    public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonText() { return reasonText; }
    public void setReasonText(String reasonText) { this.reasonText = reasonText; }
    public Date getEventTime() { return eventTime; }
    public void setEventTime(Date eventTime) { this.eventTime = eventTime; }
}
