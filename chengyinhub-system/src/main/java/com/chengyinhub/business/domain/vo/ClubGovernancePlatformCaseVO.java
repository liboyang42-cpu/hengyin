package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ClubGovernanceCase;

/** 仅平台审核队列使用；小程序我的工单/俱乐部管理端不投影证据。 */
public final class ClubGovernancePlatformCaseVO extends ClubGovernanceCaseVO {
    private String evidenceJson;

    public static ClubGovernancePlatformCaseVO from(ClubGovernanceCase source) {
        ClubGovernancePlatformCaseVO vo = fill(new ClubGovernancePlatformCaseVO(), source);
        vo.evidenceJson = source.getEvidenceJson();
        return vo;
    }

    public String getEvidenceJson() { return evidenceJson; }
}
