package com.chengyinhub.business.domain.dto;

import java.util.Date;

import com.chengyinhub.business.domain.VideoBountyEvidence;

/**
 * 证据投影:{@code capturedBy} 对外只回"是否人工录入",不回内部账号标识。
 *
 * @author chengyinhub
 */
public class VideoBountyEvidenceView
{
    private Long id;
    private Long submissionId;
    private Integer evidenceType;
    private String sourceRef;
    private Date periodStart;
    private Date periodEnd;
    private Long views;
    private Boolean organic;
    private Boolean verifiable;
    private String note;
    /** 该证据是否计入应计基数(有机且可核验)。 */
    private Boolean counted;

    public static VideoBountyEvidenceView of(VideoBountyEvidence row) {
        VideoBountyEvidenceView view = new VideoBountyEvidenceView();
        view.id = row.getId();
        view.submissionId = row.getSubmissionId();
        view.evidenceType = row.getEvidenceType();
        view.sourceRef = row.getSourceRef();
        view.periodStart = row.getPeriodStart();
        view.periodEnd = row.getPeriodEnd();
        view.views = row.getViews();
        view.organic = Integer.valueOf(1).equals(row.getOrganic());
        view.verifiable = Integer.valueOf(1).equals(row.getVerifiable());
        view.note = row.getNote();
        view.counted = view.organic && view.verifiable;
        return view;
    }

    public Long getId() { return id; }
    public Long getSubmissionId() { return submissionId; }
    public Integer getEvidenceType() { return evidenceType; }
    public String getSourceRef() { return sourceRef; }
    public Date getPeriodStart() { return periodStart; }
    public Date getPeriodEnd() { return periodEnd; }
    public Long getViews() { return views; }
    public Boolean getOrganic() { return organic; }
    public Boolean getVerifiable() { return verifiable; }
    public String getNote() { return note; }
    public Boolean getCounted() { return counted; }
}
