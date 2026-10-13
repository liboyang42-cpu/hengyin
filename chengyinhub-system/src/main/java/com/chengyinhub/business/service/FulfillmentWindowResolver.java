package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import java.util.Date;

/** W1 合并 seam；端点为 endExclusive。 */
public interface FulfillmentWindowResolver {
    Date endExclusive(CoopSettlementSnapshot snapshot);
}
