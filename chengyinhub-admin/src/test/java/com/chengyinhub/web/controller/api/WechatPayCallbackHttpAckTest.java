package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IWechatPayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP ACK contract only; does not claim SDK verification or database integration. */
class WechatPayCallbackHttpAckTest {
    private MockMvc mvc;
    private IWechatPayService gateway;
    @BeforeEach void setup() {
        ApiRegistrationController controller = new ApiRegistrationController();
        gateway = mock(IWechatPayService.class);
        com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(controller, "wechatPayService", gateway);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @Test void committedSuccessReturns204WithoutBody() throws Exception {
        when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn("{\"code\":\"SUCCESS\"}");
        mvc.perform(notification())
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(gateway).handlePayCallback(eq("{}"), argThat(headers -> headers.entrySet().stream().anyMatch(header ->
                "Wechatpay-Serial".equalsIgnoreCase(header.getKey()) && "serial".equals(header.getValue()))));
    }
    @Test void failureMissingAndMalformedAckAreNever200() throws Exception {
        for (String ack : new String[]{"{\"code\":\"FAIL\"}", null, "not-json", "{\"message\":\"SUCCESS\"}"}) {
            when(gateway.handlePayCallback(anyString(), anyMap())).thenReturn(ack);
            mvc.perform(post("/api/registration/callback").contentType("application/json").content("{}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("FAIL"));
        }
    }
    @Test void unexpectedGatewayExceptionCannotBecomeGlobalHandler200() throws Exception {
        when(gateway.handlePayCallback(anyString(), anyMap())).thenThrow(new RuntimeException("unknown"));
        mvc.perform(post("/api/registration/callback").contentType("application/json").content("{}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("FAIL"));
    }
    @Test void allFourRealGatewayRoutesRejectDomainFailuresAndExceptionsWith500() throws Exception {
        for (String no : new String[]{"CLM0123456789abcdef0123456789abc", "TPLTM_1", "DEP1", "REG1"}) {
            for (boolean throwsException : new boolean[]{false, true}) {
                RouteFixture route = new RouteFixture(no, false, throwsException);
                route.mvc.perform(notification()).andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("FAIL"));
                org.junit.jupiter.api.Assertions.assertEquals(1, route.calls.get(), no);
            }
        }
    }
    @Test void allFourRealGatewayRoutesAcknowledgeRepeatedCommittedSuccessWith204() throws Exception {
        for (String no : new String[]{"CLM0123456789abcdef0123456789abc", "TPLTM_1", "DEP1", "REG1"}) {
            RouteFixture route = new RouteFixture(no, true, false);
            route.mvc.perform(notification()).andExpect(status().isNoContent()).andExpect(content().string(""));
            route.mvc.perform(notification()).andExpect(status().isNoContent()).andExpect(content().string(""));
            org.junit.jupiter.api.Assertions.assertEquals(2, route.calls.get(), no);
        }
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder notification() {
        return post("/api/registration/callback").contentType("application/json").content("{}")
                .header("Wechatpay-Serial", "serial").header("Wechatpay-Nonce", "nonce")
                .header("Wechatpay-Signature", "signature").header("Wechatpay-Timestamp", "1");
    }
    /** Actual routing and HTTP, SDK/domain doubles: no database or cryptographic verification claim. */
    private static final class RouteFixture {
        final MockMvc mvc;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        @SuppressWarnings("unchecked")
        RouteFixture(String no, boolean success, boolean throwsException) {
            var pay = WechatVerifiedFactTestSupport.gateway();
            var parser = mock(com.wechat.pay.java.core.notification.NotificationParser.class);
            var sdk = mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
            var config = mock(com.chengyinhub.common.config.WxPayConfig.class);
            when(config.getMerchantId()).thenReturn("merchant");
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "wxPayConfig", config);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "paymentIntentJdbc", new org.springframework.jdbc.core.JdbcTemplate() {
                @Override public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
                    return java.util.List.of();
                }
            });
            when(parser.parse(any(), eq(com.wechat.pay.java.service.payments.model.Transaction.class))).thenReturn(facts(no));
            when(sdk.queryOrderByOutTradeNo(any())).thenReturn(facts(no)); // independent original-order object
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "notificationParser", parser);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "jsapiService", sdk);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "alarmService", mock(com.chengyinhub.business.service.impl.AlarmService.class));
            var club = mock(com.chengyinhub.business.service.IClubMembershipService.class);
            when(club.supportsOutTradeNo(anyString())).thenReturn(true);
            org.mockito.stubbing.Answer<Boolean> outcome = invocation -> {
                calls.incrementAndGet();
                if (throwsException) throw new IllegalStateException("domain transaction failed");
                return success;
            };
            when(club.handlePaidInvoice(eq(no),eq(true),eq(1000),eq("WX-TX"),
                    argThat(reference -> reference!=null && originalReference(pay,no).equals(reference)))).thenAnswer(outcome);
            var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(club);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "clubMembershipServiceProvider", provider);
            var commerce = mock(com.chengyinhub.business.service.IMerchantCommerceService.class);
            when(commerce.supportsOutTradeNo(anyString())).thenAnswer(c -> ((String)c.getArgument(0)).startsWith("TPLTM_"));
            when(commerce.handlePaidOrder(eq(no),eq(true),eq(1000),eq("WX-TX"),
                    argThat(reference -> reference!=null && originalReference(pay,no).equals(reference)))).thenAnswer(outcome);
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "merchantCommerceService", commerce);
            var coop = mock(com.chengyinhub.business.service.ICoopInviteService.class);
            when(coop.markDepositPaid(anyString(), any(), anyString(), any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class)))
                    .thenAnswer(invocation -> {
                        com.chengyinhub.business.service.impl.DepositVerifiedReferenceTestSupport.assertOriginal(pay,
                                invocation.getArgument(3),invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2));
                        return outcome.answer(invocation);
                    });
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "coopInviteService", coop);
            var registration = mock(com.chengyinhub.business.service.ICmsRegistrationService.class);
            if ("REG1".equals(no)) {
                var existing = new com.chengyinhub.business.domain.CmsRegistration();
                existing.setRegistrationNo(no);
                when(registration.getByRegistrationNo(no)).thenReturn(existing);
            }
            when(registration.handleWechatPayCallback(anyMap())).thenAnswer(invocation -> {
                calls.incrementAndGet();
                if (throwsException) throw new IllegalStateException("domain transaction failed");
                return success ? "SUCCESS" : "FAIL";
            });
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(pay, "registrationService", registration);
            ApiRegistrationController controller = new ApiRegistrationController();
            com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport.setField(controller, "wechatPayService", pay);
            mvc = MockMvcBuilders.standaloneSetup(controller).build();
        }
        private static com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalReference(
                com.chengyinhub.business.service.impl.WechatPayServiceImpl gateway,String no) {
            var reader=(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService)
                    WechatVerifiedFactTestSupport.getField(gateway,"verifiedFacts");
            var original=facts(no);
            var stored=reader.findPaymentSuccess(original.getMchid(),original.getOutTradeNo(),original.getTransactionId());
            org.junit.jupiter.api.Assertions.assertNotNull(stored,"HTTP routing must carry an already saved verified reference");
            var fact=reader.requireVerified(stored.reference()).facts();
            com.chengyinhub.business.service.impl.DepositVerifiedReferenceTestSupport.assertOriginal(gateway,stored.reference(),no,
                    original.getAmount().getTotal().longValue(),original.getTransactionId());
            org.junit.jupiter.api.Assertions.assertEquals(original.getOutTradeNo(),fact.wxOutTradeNo());
            org.junit.jupiter.api.Assertions.assertEquals(original.getOutTradeNo(),fact.merchantEventNo());
            org.junit.jupiter.api.Assertions.assertEquals(original.getAppid(),fact.appid());
            org.junit.jupiter.api.Assertions.assertEquals(original.getMchid(),fact.mchid());
            org.junit.jupiter.api.Assertions.assertEquals(original.getTradeType().name(),fact.tradeType());
            org.junit.jupiter.api.Assertions.assertEquals(original.getAmount().getCurrency(),fact.currency());
            org.junit.jupiter.api.Assertions.assertEquals(java.time.OffsetDateTime.parse(original.getSuccessTime()).toInstant(),fact.successAt());
            org.junit.jupiter.api.Assertions.assertNull(fact.intentRequestHash(),"this HTTP fixture explicitly uses an unfrozen historical original");
            return stored.reference();
        }
        private static com.wechat.pay.java.service.payments.model.Transaction facts(String no) {
            var tx = new com.wechat.pay.java.service.payments.model.Transaction();
            tx.setOutTradeNo(no); tx.setAppid("wx-original"); tx.setMchid("merchant"); tx.setTransactionId("WX-TX"); tx.setSuccessTime("2026-10-24T00:00:00Z");
            tx.setTradeState(com.wechat.pay.java.service.payments.model.Transaction.TradeStateEnum.SUCCESS);
            tx.setTradeType(com.wechat.pay.java.service.payments.model.Transaction.TradeTypeEnum.JSAPI);
            var amount = new com.wechat.pay.java.service.payments.model.TransactionAmount();
            amount.setTotal(1000); amount.setCurrency("CNY"); tx.setAmount(amount);
            return tx;
        }
    }

}
