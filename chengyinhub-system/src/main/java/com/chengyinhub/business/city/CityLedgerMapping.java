package com.chengyinhub.business.city;

import static com.chengyinhub.business.city.CityChecks.*;

/**
 * Required external allocation decision, NOT a default event number or an approval generator.
 * No production instance exists. The reviewer must establish registry-wide noncollision and the
 * single-net-detail/gross-city-fact audit convention before supplying this server-only contract.
 */
public final class CityLedgerMapping {
    public final int settlementEventType;
    public final String approvalId;
    public CityLedgerMapping(String approvedPurpose, int settlementEventType, String approvalId) {
        require("CITY_VISIT_SETTLEMENT".equals(approvedPurpose), "CITY_LEDGER_MAPPING_REQUIRED");
        // All visible legacy allocations 0..15 and OTHER=99 must never be borrowed for city work.
        require(settlementEventType > 15 && settlementEventType != 99, "CITY_LEDGER_EVENT_CONFLICT");
        this.settlementEventType = settlementEventType;
        this.approvalId = id(approvalId);
    }
}
