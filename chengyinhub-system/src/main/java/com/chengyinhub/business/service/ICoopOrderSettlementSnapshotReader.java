package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;

/** W2 role/scope/kinds/evidence/terms_snapshot 的合并 seam。 */
public interface ICoopOrderSettlementSnapshotReader {
    CoopSettlementSnapshot read(CoopOrder order);
}
