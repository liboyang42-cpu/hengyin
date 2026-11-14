package com.chengyinhub.business.merchantgap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;

/** crm/marketing-consents/result. */
class MarketingConsentResultServiceTest {
    @Test
    void recordedNotObservedAndConflict() {
        MerchantCrmMarketingConsentMapper mapper = mock(MerchantCrmMarketingConsentMapper.class);
        MerchantCrmMarketingConsent row = new MerchantCrmMarketingConsent();
        row.setId(41L);
        row.setMemberId(9004L);
        row.setMerchantOwnerMemberId(9002L);
        row.setMerchantRowId(8001L);
        row.setChannel("IN_APP");
        row.setEventType("OPT_IN");
        row.setRequestId("req-0001");
        row.setCreateTime(new Date(1_700_000_000_000L));
        when(mapper.selectByRequestId(9004L, 8001L, "req-0001")).thenReturn(row);
        MarketingConsentResultService service = new MarketingConsentResultService(mapper);

        Map<String, Object> recorded = service.result(9004L, 8001L, 9002L, "IN_APP", "req-0001");
        assertEquals("RECORDED", recorded.get("outcome"));
        assertEquals(1, recorded.get("receiptVersion"));
        assertEquals(9004L, recorded.get("actorId"));
        assertEquals(41L, recorded.get("consentId"));
        assertEquals("OPT_IN", recorded.get("eventType"));
        assertEquals(1_700_000_000_000L, recorded.get("recordedAt"));

        Map<String, Object> missing = service.result(9004L, 8001L, 9002L, "IN_APP", "req-0002");
        assertEquals("NOT_OBSERVED", missing.get("outcome"));
        assertNull(missing.get("consentId"));

        assertEquals(409, assertThrows(MerchantGapException.class, () -> service.result(9004L, 8001L, 9002L, "COUPON", "req-0001")).getStatus());
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.result(9004L, 8001L, 9002L, "SMS", "req-0001")).getStatus());
        assertEquals(401, assertThrows(MerchantGapException.class, () -> service.result(null, 8001L, 9002L, "IN_APP", "req-0001")).getStatus());
    }
}
