package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CommunityReportCase;

/** 申诉双方共用最小回执；即使调用者是被举报方也不会得到举报正文或证据。 */
public class CommunityReportAppealReceiptVO {
    private Long reportId;
    private String stage;
    private String publicStage;
    private Integer version;

    public static CommunityReportAppealReceiptVO from(CommunityReportCase row) {
        CommunityReportAppealReceiptVO vo = new CommunityReportAppealReceiptVO();
        vo.reportId = row.getId();
        vo.stage = row.getStage();
        vo.publicStage = CommunityReportVO.publicStage(row.getStage());
        vo.version = row.getVersion();
        return vo;
    }

    public Long getReportId() { return reportId; }
    public String getStage() { return stage; }
    public String getPublicStage() { return publicStage; }
    public Integer getVersion() { return version; }
}
