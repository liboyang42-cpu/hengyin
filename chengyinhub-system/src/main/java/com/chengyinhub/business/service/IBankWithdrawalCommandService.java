package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.WithdrawalDTO;

public interface IBankWithdrawalCommandService {
    Long submit(WithdrawalDTO request, Long memberId, String username);
}
