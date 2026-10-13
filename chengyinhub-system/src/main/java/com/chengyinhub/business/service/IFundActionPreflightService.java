package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.vo.FundActionPreflightVO;

public interface IFundActionPreflightService {
    FundActionPreflightVO prepareBankWithdrawal(Long memberId, BankWithdrawalPreflightRequest request,
                                                String contextWhere, String contextDevice);
    FundActionPreflightVO confirm(Long memberId, Long challengeId, String challengeToken);
    FundActionPreflightVO reject(Long memberId, Long challengeId, String challengeToken);

    /** 真实资金 service 必须在同一事务、首个不可逆副作用之前调用。 */
    void consumeBankWithdrawal(Long memberId, Long challengeId, BankWithdrawalPreflightRequest actualRequest);
}
