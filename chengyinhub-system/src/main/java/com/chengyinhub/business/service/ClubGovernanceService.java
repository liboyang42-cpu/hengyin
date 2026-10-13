package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ClubGovernanceService {
    List<ClubGovernanceBan> list(Long principalMemberId, Long clubId);
    ClubGovernanceBan ban(Long principalMemberId, Long clubId, Long targetMemberId,
                          String reason, Date expiresAt, String requestId);
    void unban(Long principalMemberId, Long clubId, Long banId, Integer version,
               String reason, String requestId);
    void requireJoinAllowed(Long clubId, Long memberId);
    void requireParticipationAllowed(Long clubId, Long memberId);
    ClubGovernanceOwnerTransfer transferOwner(Long principalMemberId, Long clubId,
                                               Long targetMemberId, String reason, String requestId);
    ClubGovernanceCase submitReport(Long principalMemberId, Long clubId, String targetType,
                                    Long targetId, Map<String, String> evidence,
                                    String reason, String requestId);
    ClubGovernanceCase submitAppeal(Long principalMemberId, Long clubId,
                                    String reason, String requestId);
    List<ClubGovernanceCase> listMyCases(Long principalMemberId, Long clubId);
    /**
     * CU-C-84:本人当前在指定俱乐部的有效封禁；没有就返回 null。
     * 申诉资格只有提交接口自己知道的话，前端只能让用户先白填一遍表单。
     */
    ClubGovernanceBan activeBan(Long principalMemberId, Long clubId);
    List<ClubGovernanceCase> listPlatformCases(String caseType, String status, Long clubId);
    void reviewPlatformCase(Long operatorUserId, Long caseId, Integer version,
                            boolean approved, String reason, String requestId);
    ClubGovernanceBan platformPermanentBan(Long operatorUserId, Long clubId, Long targetMemberId,
                                           String reason, String requestId);
}
