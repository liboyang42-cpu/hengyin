package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import static org.junit.jupiter.api.Assertions.*;

/** Assertions only. Uses the real gateway's persisted fact reader; never creates a success or a fallback reference. */
public final class DepositVerifiedReferenceTestSupport {
    private DepositVerifiedReferenceTestSupport() { }
    public static void assertOriginal(Object gateway, WechatVerifiedPaymentFact.Reference reference,
            String businessNo, long amountFen, String transactionId) {
        assertNotNull(reference,"The typed deposit owner must receive the original verified reference");
        assertTrue(reference.id()>0);assertNotNull(reference.hash());assertTrue(reference.hash().matches("[0-9a-f]{64}"));
        var reader=(WechatVerifiedPaymentFactService)WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
        var stored=reader.requireVerified(reference);var facts=stored.facts();
        assertEquals(reference,stored.reference());assertEquals(reference.hash(),facts.factHash());
        assertEquals(WechatVerifiedPaymentFact.Kind.PAYMENT,facts.kind());
        assertEquals(businessNo,facts.businessOutTradeNo());assertEquals(transactionId,facts.transactionId());
        assertEquals("CNY",facts.currency());assertEquals(amountFen,facts.amountFen());assertEquals(amountFen,facts.totalFen());
    }
}
