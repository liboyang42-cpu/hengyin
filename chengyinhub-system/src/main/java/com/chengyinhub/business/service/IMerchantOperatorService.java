package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MerchantInviteCreationVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorVO;
import com.chengyinhub.business.domain.vo.MerchantTeamVO;

public interface IMerchantOperatorService {
    MerchantTeamVO team(Long principalMemberId);
    MerchantInviteCreationVO invite(Long principalMemberId, String roleCode, String requestId);
    MerchantOperatorVO accept(Long principalMemberId, String rawToken, String requestId);
    /** 受邀人接受前看一眼:只凭邀请 token(待接受、未过期、商家在营)给出门店名与岗位码,不给邀请人、权限或其他团队信息。 */
    java.util.Map<String, Object> invitePreview(Long principalMemberId, String rawToken);
    MerchantOperatorVO updateRole(Long principalMemberId, Long operatorId, String roleCode,
                                  Integer version, String requestId);
    MerchantOperatorVO remove(Long principalMemberId, Long operatorId, Integer version,
                              String reason, String requestId);
    MerchantOperatorInviteVO revokeInvite(Long principalMemberId, Long inviteId, Integer version,
                                          String reason, String requestId);
}
