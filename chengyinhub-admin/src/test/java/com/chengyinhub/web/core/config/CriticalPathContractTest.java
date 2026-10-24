package com.chengyinhub.web.core.config;

import com.chengyinhub.business.service.impl.AccountDeregistrationServiceImpl;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.business.service.impl.RefundPayoutServiceImpl;
import com.chengyinhub.business.service.impl.RefundServiceImpl;
import com.chengyinhub.business.service.impl.SettlementEntryServiceImpl;
import com.chengyinhub.business.service.impl.SmsCouponHistoryServiceImpl;
import com.chengyinhub.common.annotation.CriticalPath;
import com.chengyinhub.common.observability.CriticalPathCode;
import com.chengyinhub.common.observability.CriticalPathEvents;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * O-11 合同：七条关键路径都接上了固定码，码表与交付文档一致，指标基数固定。
 * 删掉某处 @CriticalPath / 显式事件、改码名或文档漏登记都会让这里变红。
 */
class CriticalPathContractTest {
    private static final Object[][] ANNOTATED = {
            {SmsCouponHistoryServiceImpl.class, "redeemCoupon", CriticalPathCode.CP_REDEMPTION_FAILED, "coupon"},
            {PerkLedgerServiceImpl.class, "redeemChapter", CriticalPathCode.CP_REDEMPTION_FAILED, "chapter"},
            {RefundServiceImpl.class, "reviewRefund", CriticalPathCode.CP_REFUND_FAILED, "review"},
            {RefundServiceImpl.class, "handleRefundNotify", CriticalPathCode.CP_REFUND_FAILED, "notify"},
            {RefundServiceImpl.class, "markPayoutResult", CriticalPathCode.CP_REFUND_FAILED, "payout_result"},
            {RefundPayoutServiceImpl.class, "payoutAfterReview", CriticalPathCode.CP_REFUND_FAILED, "payout"},
            {SettlementEntryServiceImpl.class, "recordOnPayment", CriticalPathCode.CP_SETTLEMENT_ACCRUAL_FAILED, "payment"},
            {SettlementEntryServiceImpl.class, "recordPaidRegistration", CriticalPathCode.CP_SETTLEMENT_ACCRUAL_FAILED, "paid_registration"},
            {SettlementEntryServiceImpl.class, "recordCouponVerification", CriticalPathCode.CP_SETTLEMENT_ACCRUAL_FAILED, "coupon_verification"},
            {ConsentRecordServiceImpl.class, "record", CriticalPathCode.CP_CONSENT_WRITE_FAILED, "record"},
            {AuditTaskServiceImpl.class, "review", CriticalPathCode.CP_AUDIT_DECISION_FAILED, "review"},
            {AuditTaskServiceImpl.class, "revert", CriticalPathCode.CP_AUDIT_DECISION_FAILED, "revert"},
            {AccountDeregistrationServiceImpl.class, "apply", CriticalPathCode.CP_DEREGISTRATION_FAILED, "apply"},
            {AccountDeregistrationServiceImpl.class, "cancel", CriticalPathCode.CP_DEREGISTRATION_FAILED, "cancel"},
    };

    /** 失败在方法内被捕获、切面看不到的路径，必须在 catch 里显式发事件 */
    private static final String[][] EXPLICIT = {
            {"WxPaymentCallbackInboxServiceImpl.java", "CriticalPathEvents.failure(CriticalPathCode.CP_PAYMENT_INBOX_FAILED, \"process\", e);"},
            {"AccountDeregistrationServiceImpl.java", "CriticalPathEvents.failure(CriticalPathCode.CP_DEREGISTRATION_FAILED, \"execute\", e);"},
    };

    private static final Path SERVICE_IMPL = Paths.get(
            "../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl");
    private static final Path DOC = Paths.get("../docs/delivery/doc2-o11-o17.md");

    @Test
    void everyCriticalServiceMethodCarriesItsStableCode() {
        Set<CriticalPathCode> covered = EnumSet.noneOf(CriticalPathCode.class);
        for (Object[] row : ANNOTATED) {
            Class<?> type = (Class<?>) row[0];
            List<Method> methods = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(row[1]) && Modifier.isPublic(method.getModifiers()) && !method.isBridge()) {
                    methods.add(method);
                }
            }
            assertFalse(methods.isEmpty(), type.getSimpleName() + "." + row[1] + " 不存在");
            for (Method method : methods) {
                CriticalPath annotation = method.getAnnotation(CriticalPath.class);
                assertNotNull(annotation, method + " 缺 @CriticalPath");
                assertEquals(row[2], annotation.value(), method.toString());
                assertEquals(row[3], annotation.op(), method.toString());
                assertTrue(annotation.op().matches("[a-z][a-z0-9_]{0,31}"), method.toString());
            }
            covered.add((CriticalPathCode) row[2]);
        }
        for (String[] row : EXPLICIT) {
            String source = read(SERVICE_IMPL.resolve(row[0]));
            assertEquals(1, count(source, row[1]), row[0] + " 必须恰好一处显式事件: " + row[1]);
            covered.add(CriticalPathCode.valueOf(row[1].replaceAll(".*CriticalPathCode\\.(CP_[A-Z_]+).*", "$1")));
        }
        assertEquals(EnumSet.allOf(CriticalPathCode.class), covered, "每个事件码至少接一条真实路径");
    }

    @Test
    void deliveryDocListsEveryCodeExactlyOnceInTheEventTable() {
        String doc = read(DOC);
        for (CriticalPathCode code : CriticalPathCode.values()) {
            assertEquals(1, count(doc, "| `" + code.name() + "` |"), "事件表必须登记且只登记一次: " + code);
        }
        assertTrue(doc.contains(CriticalPathEvents.MARKER), "文档需给出 grep 标记");
        assertTrue(doc.contains(CriticalPathMetrics.METER), "文档需给出指标名");
    }

    @Test
    void metricsHaveFixedCardinalityAndTrackEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CriticalPathMetrics.bind(registry);
        assertEquals(CriticalPathCode.values().length * CriticalPathEvents.Kind.values().length,
                registry.find(CriticalPathMetrics.METER).functionCounters().size());
        FunctionCounter counter = registry.get(CriticalPathMetrics.METER)
                .tag("code", "CP_AUDIT_DECISION_FAILED").tag("kind", "FAULT").functionCounter();
        double before = counter.count();
        CriticalPathEvents.failure(CriticalPathCode.CP_AUDIT_DECISION_FAILED, "review", new NullPointerException());
        assertEquals(before + 1, counter.count());
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("读取失败: " + path, e);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
