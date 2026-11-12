package com.chengyinhub.business.domain.dto;

import java.util.Date;

/**
 * 外部平台播放证据录入请求。
 *
 * <p>只接受有 {@code sourceRef} 的外部事实或人工核验录入;{@code organic=0}
 * (付费推广/广告)与 {@code verifiable=0}(无法核验/疑似刷量)不进入应计。
 *
 * @author chengyinhub
 */
public class VideoBountyEvidenceRequest
{
    private Integer evidenceType;
    private String sourceRef;
    private Date periodStart;
    private Date periodEnd;
    private Long views;
    private Boolean organic;
    private Boolean verifiable;
    private String note;

    public Integer getEvidenceType() { return evidenceType; }
    public void setEvidenceType(Integer evidenceType) { this.evidenceType = evidenceType; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public Date getPeriodStart() { return periodStart; }
    public void setPeriodStart(Date periodStart) { this.periodStart = periodStart; }
    public Date getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Date periodEnd) { this.periodEnd = periodEnd; }
    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }
    public Boolean getOrganic() { return organic; }
    public void setOrganic(Boolean organic) { this.organic = organic; }
    public Boolean getVerifiable() { return verifiable; }
    public void setVerifiable(Boolean verifiable) { this.verifiable = verifiable; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
