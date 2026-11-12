package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * 视频有效自然播放的外部平台证据(video_bounty_evidence)。
 *
 * <p><b>反自证</b>:平台自己的设备、账号、埋点不能证明 TikTok/Reels/YouTube 的自然播放,
 * 因此每条证据必须有 {@code sourceRef}(平台后台导出/报表编号/截图摘要)与 {@code capturedBy}
 * (录入人或导入任务),只接受外部平台事实或人工核验后的录入。
 *
 * <p><b>不计应计的情形</b>:{@code organic=0}(付费推广/广告)或 {@code verifiable=0}
 * (无法核验/疑似刷量)的证据仍保留审计,但不进入应计基数。
 *
 * @author chengyinhub
 */
public class VideoBountyEvidence
{
    /** 人工核验后录入(运营看过平台后台,录数字)。 */
    public static final int TYPE_MANUAL_VERIFIED = 1;
    /** 平台导出报表导入(有可追溯的导出文件/编号)。 */
    public static final int TYPE_PLATFORM_EXPORT = 2;

    private Long id;
    private Long submissionId;
    private Integer evidenceType;
    /** 外部来源引用:平台报表编号、导出文件名摘要、截图 SHA-256 等;不接受空值。 */
    private String sourceRef;
    /** 录入人/导入任务标识。 */
    private String capturedBy;
    private Date periodStart;
    private Date periodEnd;
    /** 该区间内的播放次数(平台口径)。 */
    private Long views;
    /** 1=自然流量;0=付费/推广/广告,不计应计。 */
    private Integer organic;
    /** 1=可核验;0=无法核验/疑似刷量,不计应计。 */
    private Integer verifiable;
    private String note;
    /** 规范化字段摘要,防事后篡改;唯一键同时做录入幂等。 */
    private String evidenceHash;
    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSubmissionId() { return submissionId; }
    public void setSubmissionId(Long submissionId) { this.submissionId = submissionId; }
    public Integer getEvidenceType() { return evidenceType; }
    public void setEvidenceType(Integer evidenceType) { this.evidenceType = evidenceType; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String capturedBy) { this.capturedBy = capturedBy; }
    public Date getPeriodStart() { return periodStart; }
    public void setPeriodStart(Date periodStart) { this.periodStart = periodStart; }
    public Date getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(Date periodEnd) { this.periodEnd = periodEnd; }
    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }
    public Integer getOrganic() { return organic; }
    public void setOrganic(Integer organic) { this.organic = organic; }
    public Integer getVerifiable() { return verifiable; }
    public void setVerifiable(Integer verifiable) { this.verifiable = verifiable; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getEvidenceHash() { return evidenceHash; }
    public void setEvidenceHash(String evidenceHash) { this.evidenceHash = evidenceHash; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
