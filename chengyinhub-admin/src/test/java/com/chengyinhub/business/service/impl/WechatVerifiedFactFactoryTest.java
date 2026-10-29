package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.ARefundFactFixture;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual memory factories and canonical validation only; no SQL/SDK/transaction-isolation proof. */
class WechatVerifiedFactFactoryTest {
    @Test void actualRefundFactoryRetainsHistoricalJsapiFactsAndRejectsConflictingReplay() {
        var reader=ARefundFactFixture.memoryFacts();
        Map<String,String> original=Map.of("status","SUCCESS","currency","CNY","outTradeNo","REG77",
                "outRefundNo","RF77","total","3299","refund","1250");
        var first=ARefundFactFixture.bindSuccess(reader,original,"WX-TX77");
        var replay=ARefundFactFixture.bindSuccess(reader,original,"WX-TX77");
        assertEquals(first.get("verified_fact_id"),replay.get("verified_fact_id"));
        var reference=new WechatVerifiedPaymentFact.Reference(Long.parseLong(first.get("verified_fact_id")),first.get("verified_fact_hash"));
        var stored=reader.requireVerified(reference).facts();
        assertEquals("JSAPI",stored.tradeType());assertEquals(3299L,stored.totalFen());assertEquals(1250L,stored.amountFen());
        assertEquals("WX-TX77",stored.transactionId());assertEquals("REG77",stored.businessOutTradeNo());
        assertNull(stored.intentRequestHash(),"this explicit memory fixture models historical originals only");
        var changed=new java.util.HashMap<>(original);changed.put("refund","1251");
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->ARefundFactFixture.bindSuccess(reader,changed,"WX-TX77"));
        assertEquals(stored,reader.requireVerified(reference).facts());
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->reader.requireVerified(
                new WechatVerifiedPaymentFact.Reference(reference.id(),"f".repeat(64))));
    }

    @Test void historicalFactoryDoesNotInventAFrozenIntentOrPromoteUnknownToSuccess() {
        var facts=WechatVerifiedFactTestSupport.memoryHistoricalFacts();
        var attempted=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "fixture-mch","fixture-app","REG99","REG99","REG99","fixture-tx99","fixture-tx99",
                "JSAPI","CNY",6800,6800,Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),"a".repeat(64),"SUCCESS");
        assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->facts.recordVerified(attempted));
        assertNull(facts.findPaymentSuccess("fixture-mch","REG99","fixture-tx99"));
        var invalid=Map.of("status","UNKNOWN","currency","CNY","outTradeNo","REG77","outRefundNo","RF77","total","3299","refund","1250");
        assertThrows(IllegalArgumentException.class,()->ARefundFactFixture.bindSuccess(facts,invalid,"WX-TX77"));
    }

    @Test void fixtureEvidenceIsSeededBeforeTheBusinessTransactionAndOnlyItsReferenceIsReadInside() {
        var facts=WechatVerifiedFactTestSupport.memoryHistoricalFacts();
        var original=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "fixture-mch","fixture-app","REG99","REG99","REG99","fixture-tx99","fixture-tx99",
                "JSAPI","CNY",6800,6800,Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),null,"SUCCESS");
        var reference=facts.recordVerified(original);
        new TransactionTemplate(WechatVerifiedFactTestSupport.unitBusinessTransactions()).executeWithoutResult(status->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(original,facts.requireVerified(reference).facts());
            // Real common consumer, explicit historical no-intent SQL port; never a frozen-attempt proof.
            assertEquals(reference,facts.requireConsumablePayment(reference).reference());
            assertEquals(reference,facts.requireConsumablePayment(reference).reference());
            assertThrows(IllegalStateException.class,()->facts.recordVerified(original));
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(reference,facts.recordVerified(original));
    }

    @Test void historicalFactoryCannotConsumeAnAliasedProviderAsAnOriginalLegacyNumber() {
        var facts=WechatVerifiedFactTestSupport.memoryHistoricalFacts();
        var original=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,
                "fixture-mch","fixture-app","REG99","WXP-OLD","WXP-OLD","fixture-tx99","fixture-tx99",
                "JSAPI","CNY",6800,6800,Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME),null,"SUCCESS");
        var reference=facts.recordVerified(original);
        new TransactionTemplate(WechatVerifiedFactTestSupport.unitBusinessTransactions()).executeWithoutResult(status->{
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,
                    ()->facts.requireConsumablePayment(reference));
        });
        assertEquals(original,facts.requireVerified(reference).facts(),"rejection retains the original evidence");
        assertTrue(facts.findPaymentRecovery(reference).isEmpty());
    }

}
