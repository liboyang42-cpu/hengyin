package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.service.FulfillmentWindowResolver;
import org.springframework.stereotype.Service;

import java.util.Date;

/** 当前窄 seam；W1 合并时改接其统一计算器。 */
@Service
public class SnapshotFulfillmentWindowResolver implements FulfillmentWindowResolver {
    @Override
    public Date endExclusive(CoopSettlementSnapshot snapshot) {
        return snapshot == null ? null : snapshot.getFulfillmentEndExclusive();
    }
}
