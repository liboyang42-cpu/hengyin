package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.*;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.chengyinhub.business.service.impl.WechatPayTestWiring;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Gateway SDK doubles only. Query fixtures are independent of notifications; no database claim. */
class WechatPayCallbackFactsTest {
    private static final String[] ORDERS = {"CLM0123456789abcdef0123456789abc", "TPLTM_1", "DEP1T100", "REG1"};

    @Test
    void actualFourRoutesAndBothExistingAppRoutesRunWithTheInitializedSdkAlone() {
        for (String order : ORDERS) {
            Fixture f = new Fixture(order, "JSAPI");
            assertTrue(f.deliver().contains("SUCCESS"), order);
            f.verifyRouted();
            verify(f.sdk).queryOrderByOutTradeNo(argThat(q -> order.equals(q.getOutTradeNo()) && "merchant".equals(q.getMchid())));
        }
        for (String order : new String[]{"DEP1T100", "REG1"}) {
            Fixture f = new Fixture(order, "APP");
            assertTrue(f.deliver().contains("SUCCESS"));
            f.verifyRouted();
        }
    }

    @Test
    void fourRoutesRejectWrongOrMissingCallbackFactsWithoutBusinessChanges() {
        for (String order : ORDERS) {
            for (String invalid : new String[]{"appid", "mchid", "currency", "amount", "order", "transaction",
                    "tradeType", "missingAppid", "missingMchid", "missingCurrency", "missingAmount",
                    "missingTotal", "missingTransaction", "missingOrder", "missingState", "zeroAmount"}) {
                Fixture f = new Fixture(order, "JSAPI");
                change(f.transaction, invalid);
                assertTrue(f.deliver().contains("FAIL"), order + "/" + invalid);
                f.verifyNoBusinessCalls();
            }
        }
    }

    @Test
    void unavailableMissingOrMalformedOriginalOrderNeverGuessesAnAppid() {
        for (String order : ORDERS) {
            Fixture missing = new Fixture(order, "JSAPI");
            missing.createdOrders.clear();
            assertTrue(missing.deliver().contains("FAIL"));
            missing.verifyNoBusinessCalls();
            for (String bad : new String[]{"appid", "mchid", "currency", "amount", "order", "transaction",
                    "tradeType", "missingAppid", "missingMchid", "missingCurrency", "missingAmount",
                    "missingTotal", "missingTransaction", "missingOrder", "missingState", "zeroAmount"}) {
                Fixture f = new Fixture(order, "JSAPI");
                change(f.createdOrders.get(order), bad);
                assertTrue(f.deliver().contains("FAIL"), order + "/original/" + bad);
                f.verifyNoBusinessCalls();
            }
            Fixture unavailable = new Fixture(order, "JSAPI");
            WechatPayTestWiring.setField(unavailable.gateway, "jsapiService", null);
            assertTrue(unavailable.deliver().contains("FAIL"));
            unavailable.verifyNoBusinessCalls();
            for (String code : new String[]{"ORDER_NOT_EXIST", "SYSTEM_ERROR", "NEW_CODE"}) {
                Fixture failed = new Fixture(order, "JSAPI");
                ServiceException error = mock(ServiceException.class);
                when(error.getErrorCode()).thenReturn(code);
                when(error.getHttpStatusCode()).thenReturn("ORDER_NOT_EXIST".equals(code) ? 404 : 500);
                doThrow(error).when(failed.sdk).queryOrderByOutTradeNo(any());
                assertTrue(failed.deliver().contains("FAIL"), code);
                failed.verifyNoBusinessCalls();
            }
            Fixture timeout = new Fixture(order, "JSAPI");
            doThrow(new RuntimeException("query timeout")).when(timeout.sdk).queryOrderByOutTradeNo(any());
            assertTrue(timeout.deliver().contains("FAIL"));
            timeout.verifyNoBusinessCalls();
        }
    }

    @Test
    void duplicateNotificationsKeepTheDomainDecisionAndLateStatesCannotReverseIt() {
        for (String order : ORDERS) {
            Fixture f = new Fixture(order, "JSAPI");
            assertTrue(f.deliver().contains("SUCCESS"));
            assertTrue(f.deliver().contains("SUCCESS"));
            // The existing domain CAS may still reject a state/transaction binding conflict.
            f.rejectDomain = true;
            assertTrue(f.deliver().contains("FAIL"));
            clearInvocations(f.membership, f.commerce, f.coop, f.registration);
            f.transaction.setTransactionId("OTHER_TX");
            assertTrue(f.deliver().contains("FAIL"));
            f.transaction.setTransactionId("TX1");
            for (Transaction.TradeStateEnum late : new Transaction.TradeStateEnum[]{
                    Transaction.TradeStateEnum.CLOSED, Transaction.TradeStateEnum.NOTPAY,
                    Transaction.TradeStateEnum.REFUND, Transaction.TradeStateEnum.PAYERROR}) {
                f.transaction.setTradeState(late);
                // Original paid order stays SUCCESS: a late conflicting notification cannot rewrite that fact.
                assertTrue(f.deliver().contains("FAIL"));
            }
            f.verifyNoBusinessCalls();
            Fixture refunded = new Fixture(order, "JSAPI");
            refunded.createdOrders.get(order).setTradeState(Transaction.TradeStateEnum.REFUND);
            assertTrue(refunded.deliver().contains("FAIL"));
            refunded.verifyNoBusinessCalls();
        }
    }

    @Test
    void registrationReceivesCompleteVerifiedFactsAndDomainRejectionStaysFailure() {
        Fixture f = new Fixture("REG1", "APP");
        assertTrue(f.deliver().contains("SUCCESS"));
        verify(f.registration).handleWechatPayCallback(argThat(facts -> "REG1".equals(facts.get("out_trade_no"))
                && "TX1".equals(facts.get("transaction_id")) && "5000".equals(facts.get("total"))
                && "wx-app".equals(facts.get("appid")) && "merchant".equals(facts.get("mchid"))
                && "CNY".equals(facts.get("currency")) && "APP".equals(facts.get("trade_type"))));
        for (String order : ORDERS) {
            Fixture rejected = new Fixture(order, "JSAPI");
            rejected.rejectDomain = true;
            assertTrue(rejected.deliver().contains("FAIL"));
        }
    }

    @Test
    void persistentIntentMismatchCannotBeReplacedByMatchingNotificationAndQuery() {
        Fixture f = new Fixture("REG1", "JSAPI");
        f.intentDb.intents.put("merchant:REG1", new HashMap<>(Map.of("business_out_trade_no", "REG1", "appid", "DIFFERENT_FROZEN_APP",
                "trade_type", "JSAPI", "currency", "CNY", "total_fen", 5000L)));
        assertTrue(f.deliver().contains("FAIL"));
        f.verifyNoBusinessCalls();
        assertEquals("DIFFERENT_FROZEN_APP", f.intentDb.intents.get("merchant:REG1").get("appid"));
    }

    @Test
    void verifiedClosedOrderReleasesActiveConstraintWithoutChangingOriginalFacts() {
        Fixture f = new Fixture("REG1", "JSAPI");
        f.intentDb.intents.put("merchant:REG1", new HashMap<>(Map.of("business_out_trade_no", "REG1", "appid", "wx-mini",
                "trade_type", "JSAPI", "currency", "CNY", "total_fen", 5000L, "request_hash", "original-hash")));
        f.createdOrders.get("REG1").setTradeState(Transaction.TradeStateEnum.CLOSED);
        f.createdOrders.get("REG1").setTradeType(null);
        f.createdOrders.get("REG1").setTransactionId(null);
        assertEquals("CLOSED", f.gateway.queryOrderStatus("REG1").get("trade_state"));
        assertNotNull(f.intentDb.intents.get("merchant:REG1").get("closed_confirmed_at"));
        assertEquals("original-hash", f.intentDb.intents.get("merchant:REG1").get("request_hash"));
        f.verifyNoBusinessCalls();
    }

    @Test
    void rejectedLateCallbackCannotReleaseChannelLockEvenIfOriginalIsClosed() {
        Fixture f = new Fixture("REG1", "JSAPI");
        f.intentDb.intents.put("merchant:REG1", new HashMap<>(Map.of("business_out_trade_no", "REG1", "appid", "wx-mini",
                "trade_type", "JSAPI", "currency", "CNY", "total_fen", 5000L)));
        f.createdOrders.get("REG1").setTradeState(Transaction.TradeStateEnum.CLOSED);
        assertTrue(f.deliver().contains("FAIL"));
        assertNull(f.intentDb.intents.get("merchant:REG1").get("closed_confirmed_at"));
        f.verifyNoBusinessCalls();
    }

    @Test
    void newAttemptAliasRoutesAllFourDomainsAndQueryKeepsBusinessIdentity() {
        for (String business : ORDERS) {
            Fixture f = new Fixture(business, "APP");
            String alias = "WXP0123456789abcdef0123456789abc";
            f.transaction.setOutTradeNo(alias);
            Transaction actual = order(alias, "APP");
            f.createdOrders.clear();
            f.createdOrders.put(alias, actual);
            var row = new HashMap<String, Object>();
            row.put("mchid", "merchant"); row.put("out_trade_no", alias);
            row.put("business_out_trade_no", business); row.put("attempt_no", 2);
            row.put("appid", "wx-app"); row.put("trade_type", "APP");
            row.put("currency", "CNY"); row.put("total_fen", 5000L);
            f.intentDb.intents.put("merchant:" + alias, row);
            assertTrue(f.deliver().contains("SUCCESS"), business);
            f.verifyRouted();
            Map<String, String> facts = f.gateway.queryOrderStatus(business);
            assertEquals(business, facts.get("out_trade_no"));
            assertEquals(alias, facts.get("wx_out_trade_no"));
            assertEquals("wx-app", facts.get("appid"));
        }
    }

    private static void change(Transaction t, String invalid) {
        switch (invalid) {
            case "appid" -> t.setAppid("wx-app"); // another configured appid is not the original order appid
            case "mchid" -> t.setMchid("another-merchant");
            case "currency" -> t.getAmount().setCurrency("USD");
            case "amount" -> t.getAmount().setTotal(4999);
            case "order" -> t.setOutTradeNo("OTHER_ORDER");
            case "transaction" -> t.setTransactionId("OTHER_TX");
            case "tradeType" -> t.setTradeType(Transaction.TradeTypeEnum.APP);
            case "missingAppid" -> t.setAppid(null);
            case "missingMchid" -> t.setMchid(null);
            case "missingCurrency" -> t.getAmount().setCurrency(null);
            case "missingAmount" -> t.setAmount(null);
            case "missingTotal" -> t.getAmount().setTotal(null);
            case "missingTransaction" -> t.setTransactionId(null);
            case "missingOrder" -> t.setOutTradeNo(null);
            case "missingState" -> t.setTradeState(null);
            case "zeroAmount" -> t.getAmount().setTotal(0);
            default -> throw new IllegalArgumentException(invalid);
        }
    }

    private static Transaction order(String no, String channel) {
        Transaction t = new Transaction();
        t.setOutTradeNo(no);
        t.setTransactionId("TX1");
        t.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        t.setTradeType(Transaction.TradeTypeEnum.valueOf(channel));
        t.setMchid("merchant");
        t.setAppid("APP".equals(channel) ? "wx-app" : "wx-mini");
        TransactionAmount amount = new TransactionAmount();
        amount.setTotal(5000);
        amount.setCurrency("CNY");
        t.setAmount(amount);
        return t;
    }

    private static class Fixture {
        final WechatPayServiceImpl gateway = WechatPayTestWiring.facade();
        final JsapiService sdk = mock(JsapiService.class);
        final ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        final IClubMembershipService membership = mock(IClubMembershipService.class);
        final IMerchantCommerceService commerce = mock(IMerchantCommerceService.class);
        final ICoopInviteService coop = mock(ICoopInviteService.class);
        final Map<String, Transaction> createdOrders = new HashMap<>();
        final Transaction transaction;
        final WechatPaymentIntentTestSupport intentDb;
        final String order;
        boolean rejectDomain;

        @SuppressWarnings("unchecked")
        Fixture(String no, String channel) {
            intentDb = WechatPaymentIntentTestSupport.install(gateway, Map.of(no, new java.math.BigDecimal("50.00")));
            order = no;
            transaction = order(no, channel);
            createdOrders.put(no, order(no, channel));
            when(sdk.queryOrderByOutTradeNo(any())).thenAnswer(c ->
                    createdOrders.get(((QueryOrderByOutTradeNoRequest) c.getArgument(0)).getOutTradeNo()));
            WechatPayTestWiring.setField(gateway, "jsapiService", sdk);
            NotificationParser parser = mock(NotificationParser.class);
            when(parser.parse(any(), eq(Transaction.class))).thenReturn(transaction);
            WechatPayTestWiring.setField(gateway, "notificationParser", parser);
            WechatPayTestWiring.setField(gateway, "alarmService", mock(AlarmService.class));
            WxPayConfig config = mock(WxPayConfig.class);
            when(config.getMerchantId()).thenReturn("merchant");
            lenient().when(config.getAppid()).thenReturn("wx-mini");
            lenient().when(config.getAppAppid()).thenReturn("wx-app");
            WechatPayTestWiring.setField(gateway, "wxPayConfig", config);
            ObjectProvider<IClubMembershipService> clubs = mock(ObjectProvider.class);
            when(clubs.getIfAvailable()).thenReturn(membership);
            WechatPayTestWiring.setField(gateway, "clubMembershipServiceProvider", clubs);
            WechatPayTestWiring.setField(gateway, "registrationService", registration);
            WechatPayTestWiring.setField(gateway, "merchantCommerceService", commerce);
            WechatPayTestWiring.setField(gateway, "coopInviteService", coop);
            lenient().when(membership.supportsOutTradeNo(anyString())).thenAnswer(c -> no.equals(c.getArgument(0)));
            lenient().when(commerce.supportsOutTradeNo(anyString())).thenAnswer(c -> ((String) c.getArgument(0)).startsWith("TPLTM_"));
            lenient().when(membership.handlePaidInvoice(eq(no), eq(true), eq(5000), anyString())).thenAnswer(c -> !rejectDomain);
            lenient().when(commerce.handlePaidOrder(eq(no), eq(true), eq(5000), anyString())).thenAnswer(c -> !rejectDomain);
            lenient().when(coop.markDepositPaid(eq(no), eq(5000L), anyString())).thenAnswer(c -> !rejectDomain);
            lenient().when(registration.handleWechatPayCallback(anyMap())).thenAnswer(c -> rejectDomain ? "FAIL" : "SUCCESS");
        }

        String deliver() {
            return gateway.handlePayCallback("signed-body-double", Map.of("wechatpay-serial", "serial",
                    "wechatpay-nonce", "nonce", "wechatpay-signature", "signature", "wechatpay-timestamp", "1"));
        }

        void verifyNoBusinessCalls() { verifyNoInteractions(registration, membership, commerce, coop); }

        void verifyRouted() {
            if (order.startsWith("CLM")) verify(membership).handlePaidInvoice(order, true, 5000, "TX1");
            else if (order.startsWith("TPLTM_")) verify(commerce).handlePaidOrder(order, true, 5000, "TX1");
            else if (order.startsWith("DEP")) verify(coop).markDepositPaid(order, 5000L, "TX1");
            else verify(registration).handleWechatPayCallback(anyMap());
        }
    }
}
