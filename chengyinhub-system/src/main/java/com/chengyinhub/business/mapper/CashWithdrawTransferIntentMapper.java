package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import org.apache.ibatis.annotations.Param;

public interface CashWithdrawTransferIntentMapper {
    CashWithdrawTransferIntent selectByWithdrawId(Long withdrawId);
    int insert(CashWithdrawTransferIntent intent);
    int markSubmitted(@Param("withdrawId") Long withdrawId, @Param("requestHash") String requestHash);
    int markLocallyRejected(@Param("withdrawId") Long withdrawId, @Param("requestHash") String requestHash);
}
