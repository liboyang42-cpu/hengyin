package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.domain.dto.RefundSettledFact;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款到账广播契约：只有 SUCCESS 且 CAS 成功才广播；无不可变事实不广播；消费方异常必须上抛。
 *
 * <p>注：本测试用反射直调私有方法，只证明广播守卫与事实内容，
 * <b>不</b>证明 InnoDB 事务回滚，也不证明微信回调 ACK 行为（那属 MySqlIT / 联调）。
 */
class RefundSettledConsumerContractTest {

    private static final class RecordingConsumer implements IRefundSettledConsumer {
        final List<RefundSettledFact> seen = new ArrayList<>();
        boolean explode;

        @Override
        public void onRefundSettled(RefundSettledFact fact) {
            seen.add(fact);
            if (explode) {
                throw new IllegalStateException("consumer down");
            }
        }
    }

    private RefundServiceImpl serviceWith(RecordingConsumer... consumers) {
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundSettledConsumers", List.of(consumers));
        return service;
    }

    private RefundApplication refund() {
        RefundApplication refund = new RefundApplication();
        refund.setId(41L);
        refund.setSourceType("registration");
        refund.setSourceId(900L);
        refund.setRefundNo("RF900");
        refund.setRefundAmount(new BigDecimal("12.34"));
        return refund;
    }

    @Test
    void settledFactCarriesVerifiedIdentityAndFenAmounts() {
        RefundSettledFact fact = new RefundSettledFact(900L, 41L, "RF900", "WXREFUND1",
                1234L, 5000L, "CNY", 77L, "a".repeat(64));

        assertEquals(Long.valueOf(900L), fact.registrationId());
        assertEquals(Long.valueOf(41L), fact.refundApplicationId());
        assertEquals(1234L, fact.refundedFen());
        assertEquals(5000L, fact.totalFen());
        assertEquals(new BigDecimal("12.34"), fact.refundedAmount());
        assertEquals(new BigDecimal("50.00"), fact.totalAmount());
        assertEquals("refund-settled:900:41", fact.idempotencyKey());
    }

    @Test
    void consumersReceiveTheFactOnlyThroughTheDedicatedBroadcast() {
        RecordingConsumer consumer = new RecordingConsumer();
        RefundServiceImpl service = serviceWith(consumer);
        RefundSettledFact fact = new RefundSettledFact(900L, 41L, "RF900", "WXREFUND1",
                1234L, 5000L, "CNY", 77L, "a".repeat(64));

        ReflectionTestUtils.invokeMethod(service, "notifyRefundSettled", refund(), stored(fact));

        assertEquals(1, consumer.seen.size());
        assertEquals(fact.idempotencyKey(), consumer.seen.get(0).idempotencyKey());
    }

    @Test
    void aFailingConsumerMustPropagateSoTheCallbackRollsBackInsteadOfSilentlySkipping() {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.explode = true;
        RefundServiceImpl service = serviceWith(consumer);
        RefundSettledFact fact = new RefundSettledFact(900L, 41L, "RF900", "WXREFUND1",
                1234L, 5000L, "CNY", 77L, "a".repeat(64));
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "notifyRefundSettled", refund(), stored(fact)));
        // Spring 可能原样抛目标异常,也可能包一层;两种都必须能看出是消费方失败,不能被吞掉。
        String text = String.valueOf(thrown) + "|" + (thrown.getCause() == null ? "" : thrown.getCause());
        assertTrue(text.contains("consumer down"), "消费方异常必须可追溯: " + text);
    }

    @Test
    void withoutVerifiedFactNothingIsBroadcast() {
        RecordingConsumer consumer = new RecordingConsumer();
        RefundServiceImpl service = serviceWith(consumer);

        ReflectionTestUtils.invokeMethod(service, "notifyRefundSettled", refund(), null);

        assertTrue(consumer.seen.isEmpty(), "旧回调路径无不可变事实时不得凭字段假装已到账");
    }

    @Test
    void nonRegistrationSourceIsSkipped() {
        RecordingConsumer consumer = new RecordingConsumer();
        RefundServiceImpl service = serviceWith(consumer);
        RefundApplication deposit = refund();
        deposit.setSourceType("deposit");
        RefundSettledFact fact = new RefundSettledFact(900L, 41L, "RF900", "WXREFUND1",
                1234L, 5000L, "CNY", 77L, "a".repeat(64));

        ReflectionTestUtils.invokeMethod(service, "notifyRefundSettled", deposit, stored(fact));

        assertTrue(consumer.seen.isEmpty());
    }

    @Test
    void noConsumersMeansNoWorkAndNoFailure() {
        RefundServiceImpl service = new RefundServiceImpl();
        ReflectionTestUtils.setField(service, "refundSettledConsumers", null);

        // 不抛异常即通过：未装配消费方时改造前后行为一致。
        ReflectionTestUtils.invokeMethod(service, "notifyRefundSettled", refund(), null);
        assertTrue(true);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code Stored} 是 public record，直接构造即可：真实服务里它由 {@code requireVerified}
     * 产出并做金额/身份全等校验，这里只覆盖广播侧的守卫与字段搬运。
     */
    private com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored stored(
            RefundSettledFact fact) {
        com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact facts =
                new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                        com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.REFUND,
                        "1".repeat(32), "2".repeat(32), "RF900", "WXOUT1", "RF900", fact.providerRefundId(),
                        "TX1", "JSAPI", fact.currency(), fact.totalFen(), fact.refundedFen(),
                        java.time.Instant.parse("2026-11-08T00:00:00Z"), null, "REFUND");
        com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference =
                new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(
                        fact.verifiedRefundFactId(), fact.verifiedRefundFactHash());
        return new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference, facts);
    }
}
