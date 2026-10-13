package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MerchantInviteCreationVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorVO;
import com.chengyinhub.business.domain.vo.MerchantTeamVO;

public interface IMerchantOperatorService {
    MerchantTeamVO team(Long principalMemberId);
    MerchantInviteCreationVO invite(Long principalMemberId, String roleCode, String requestId);
    MerchantOperatorVO accept(Long principalMemberId, String rawToken, String requestId);
    MerchantOperatorVO updateRole(Long principalMemberId, Long operatorId, String roleCode,
                                  Integer version, String requestId);
    MerchantOperatorVO remove(Long principalMemberId, Long operatorId, Integer version,
                              String reason, String requestId);
    MerchantOperatorInviteVO revokeInvite(Long principalMemberId, Long inviteId, Integer version,
                                          String reason, String requestId);
}
