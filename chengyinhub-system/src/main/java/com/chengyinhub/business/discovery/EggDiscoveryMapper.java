package com.chengyinhub.business.discovery;

import org.apache.ibatis.annotations.Param;
import static com.chengyinhub.business.discovery.EggDiscoveryRows.*;

public interface EggDiscoveryMapper {
    int insertScope(ScopeRow row);
    ScopeRow lockScope(@Param("evaluationKey") String evaluationKey, @Param("memberId") long memberId);
    ReceiptRow lockReceipt(@Param("evaluationKey") String evaluationKey, @Param("memberId") long memberId);
    int insertReceipt(ReceiptRow row);
    int revoke(@Param("evaluationKey") String evaluationKey, @Param("memberId") long memberId,
               @Param("revision") long revision);
    int insertOutbox(OutboxRow row);
}
