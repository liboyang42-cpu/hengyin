package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WorkshopMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkshopWechatPaymentOrderAdapterTest {
    private static final String NO = "WKO_" + "1".repeat(32);
    private final WorkshopMapper mapper = mock(WorkshopMapper.class);
    private final WechatVerifiedPaymentFactService verified = mock(WechatVerifiedPaymentFactService.class);
    private final List<String> completion = new ArrayList<>();
    private final WorkshopWechatPaymentOrderAdapter adapter = new WorkshopWechatPaymentOrderAdapter(mapper, verified,
            new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
                @Override protected void doCommit(DefaultTransactionStatus status) { completion.add("commit"); }
                @Override protected void doRollback(DefaultTransactionStatus status) { completion.add("rollback"); }
            });

    @Test void originalLockedIdentityOwnsCasAndRecoveryInOneTransaction() {
        var fact = payment();
        var reference = new Reference(3, fact.factHash());
        var row = order();
        when(mapper.lockOrder(NO)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return row;
        });
        when(verified.requireConsumablePayment(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference, fact));
        when(mapper.activate(anyMap())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Map<String,Object> update = call.getArgument(0);
            assertEquals(Map.of("orderNo", NO, "owner", 7L, "factId", 3L,
                    "factHash", reference.hash(), "paidAt", Date.from(fact.successAt())), update);
            return 1;
        });

        assertTrue(adapter.handlePaidOrder(observation(reference)));
        var sequence = inOrder(mapper, verified);
        sequence.verify(mapper).lockOrder(NO);
        sequence.verify(verified).requireConsumablePayment(reference);
        sequence.verify(mapper).activate(anyMap());
        sequence.verify(verified).completePaymentRecovery(reference, 91L);
        assertEquals(List.of("commit"), completion);
    }

    @Test void lostCasRollsBackWithoutCompletingRecovery() {
        var fact = payment();
        var reference = new Reference(3, fact.factHash());
        when(mapper.lockOrder(NO)).thenReturn(order());
        when(verified.requireConsumablePayment(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference, fact));
        when(mapper.activate(anyMap())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> adapter.handlePaidOrder(observation(reference)));
        verify(verified, never()).completePaymentRecovery(any(), anyLong());
        assertEquals(List.of("rollback"), completion);
    }

    @Test void unknownOriginalCannotConsumeFactAndMalformedOrderCannotLock() {
        var reference = new Reference(3, payment().factHash());
        assertFalse(adapter.handlePaidOrder(Map.of("out_trade_no", "WKO_unknown", "trade_state", "SUCCESS")));
        verifyNoInteractions(mapper, verified);
        assertFalse(adapter.handlePaidOrder(observation(reference)));
        verify(mapper).lockOrder(NO);
        verifyNoInteractions(verified);
    }

    private static Map<String,Object> order() {
        return new HashMap<>(Map.of("id", 91L, "order_no", NO, "buyer_id", 7L,
                "amount_fen", 123L, "provider", "WECHAT_APP", "state", "PENDING"));
    }

    private static WechatVerifiedPaymentFact payment() {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, "mch", "app",
                NO, "wx1", "wx1", "tx1", "tx1", "APP", "CNY", 123, 123,
                Instant.ofEpochSecond(1), null, "SUCCESS");
    }

    private static Map<String,String> observation(Reference reference) {
        return Map.of("out_trade_no", NO, "trade_state", "SUCCESS",
                "verified_fact_id", Long.toString(reference.id()), "verified_fact_hash", reference.hash());
    }
}

