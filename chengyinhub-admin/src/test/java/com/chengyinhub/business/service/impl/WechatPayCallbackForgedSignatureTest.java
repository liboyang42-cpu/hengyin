package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.IMerchantCommerceService;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import org.junit.jupiter.api.Test;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * K块负控:伪造/篡改微信支付回调签名必须被真实拒绝且零副作用。
 *
 * 现状(P1-6只读预检已确认):handlePayCallback/handleRefundCallback 完全委托官方 SDK
 * NotificationParser 验签,repo 里此前没有任何测试用真实构造的签名+密文去验证"伪造一定被拒"这件事——
 * 已有测试全部只在验签*之后*的业务 Map/String 参数上打转(见 RefundServiceImplTest 等)。
 *
 * 本测试不 mock NotificationParser,而是用自己生成的密钥对+RSAPublicKeyNotificationConfig
 * 构造一个真实可验签的 NotificationParser 实例(与生产用 RSAAutoCertificateConfig 只是证书来源不同,
 * 验签/解密算法路径完全一致:RSAVerifier + AeadAesCipher),对同一份"合法构造"做正控,
 * 再仅篡改一个变量做负控——避免"只 assertThrows 不看错哪一道闸"的假绿。
 */
class WechatPayCallbackForgedSignatureTest {

    private static final String PUBLIC_KEY_ID = "TEST-SERIAL-0001";
    private static final String API_V3_KEY = "0123456789abcdef0123456789ABCDEF"; // 32 bytes -> AES-256

    // ---------- SDK 级正控/负控:证明真实签名验证确实会拒绝伪造/篡改 ----------

    @Test
    void validSignedAndEncryptedNotificationParsesSuccessfully() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);

        Transaction transaction = parser.parse(toRequestParam(n), Transaction.class);

        assertEquals("REG20260725001", transaction.getOutTradeNo());
        assertEquals("TXN123", transaction.getTransactionId());
        assertEquals(Transaction.TradeStateEnum.SUCCESS, transaction.getTradeState());
        assertEquals(1990, transaction.getAmount().getTotal());
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);

        byte[] sig = Base64.getDecoder().decode(n.signature);
        sig[0] ^= 0xFF; // 翻转签名首字节,模拟伪造/篡改
        n.signature = Base64.getEncoder().encodeToString(sig);

        assertThrows(ValidationException.class, () -> parser.parse(toRequestParam(n), Transaction.class));
    }

    @Test
    void tamperedBodyAfterSigningIsRejected() throws Exception {
        // 攻击者截获合法回调后篡改金额,但签名保持不变(拿不到私钥就没法重新签)。
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);

        n.body = n.body.replace("\"id\":\"evt-1\"", "\"id\":\"evt-1-tampered\"");

        assertThrows(ValidationException.class, () -> parser.parse(toRequestParam(n), Transaction.class));
    }

    @Test
    void signatureFromUnrelatedKeyPairIsRejected() throws Exception {
        // 攻击者自己起一对密钥伪造整条回调(拿不到商户平台真实私钥),验签方仍按配置好的公钥/证书验证。
        KeyPair legit = generateKeyPair();
        KeyPair attacker = generateKeyPair();
        NotificationParser parser = buildParser(legit.getPublic()); // 验签方只认 legit 的公钥
        Forged n = buildValidPayNotification(attacker.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);

        assertThrows(ValidationException.class, () -> parser.parse(toRequestParam(n), Transaction.class));
    }

    @Test
    void unknownSerialNumberIsRejected() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);
        n.serial = "SOME-OTHER-SERIAL";

        assertThrows(ValidationException.class, () -> parser.parse(toRequestParam(n), Transaction.class));
    }

    @Test
    void refundNotificationValidSignatureParsesSuccessfully() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidRefundNotification(kp.getPrivate(), "RF12345", "SUCCESS");

        RefundNotification notif = parser.parse(toRequestParam(n), RefundNotification.class);

        assertEquals("RF12345", notif.getOutRefundNo());
        assertEquals(com.wechat.pay.java.service.refund.model.Status.SUCCESS, notif.getRefundStatus());
    }

    @Test
    void refundNotificationTamperedSignatureIsRejected() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        Forged n = buildValidRefundNotification(kp.getPrivate(), "RF12345", "SUCCESS");

        byte[] sig = Base64.getDecoder().decode(n.signature);
        sig[sig.length - 1] ^= 0xFF;
        n.signature = Base64.getEncoder().encodeToString(sig);

        assertThrows(ValidationException.class, () -> parser.parse(toRequestParam(n), RefundNotification.class));
    }

    // ---------- Service 级:验证伪造回调在 WechatPayServiceImpl 里不产生任何业务副作用 ----------

    @Test
    void handlePayCallbackWithValidSignatureInvokesRegistrationServiceExactlyOnce() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICoopInviteService coopInviteService = mock(ICoopInviteService.class);
        wireParser(service, parser, registrationService, coopInviteService, mock(IRefundService.class));
        when(registrationService.handleWechatPayCallback(anyMap())).thenReturn("SUCCESS");

        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);
        String resp = service.handlePayCallback(n.body, headersOf(n));

        assertTrue(resp.contains("SUCCESS"));
        verify(registrationService).handleWechatPayCallback(anyMap());
        verifyNoInteractions(coopInviteService);
        verifyNoInteractions((AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService"));
    }

    @Test
    void handlePayCallbackWithCommercePrefixRoutesVerifiedAmountAndTransaction() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICoopInviteService coopInviteService = mock(ICoopInviteService.class);
        wireParser(service, parser, registrationService, coopInviteService, mock(IRefundService.class));
        IMerchantCommerceService commerceService = mock(IMerchantCommerceService.class);
        WechatVerifiedFactTestSupport.setField(service, "merchantCommerceService", commerceService);
        when(commerceService.supportsOutTradeNo("TPLTM_20260805")).thenReturn(true);
        when(commerceService.handlePaidOrder(eq("TPLTM_20260805"), eq(true), eq(1990), eq("TXN-COMMERCE"),
                argThat(ref -> matchesReference(service,ref,"TPLTM_20260805",1990,"TXN-COMMERCE"))))
                .thenReturn(true);

        Forged n = buildValidPayNotification(kp.getPrivate(), "TPLTM_20260805", "TXN-COMMERCE", "SUCCESS", 1990);
        String resp = service.handlePayCallback(n.body, headersOf(n));

        assertTrue(resp.contains("SUCCESS"));
        verify(commerceService).handlePaidOrder(eq("TPLTM_20260805"),eq(true),eq(1990),eq("TXN-COMMERCE"),
                argThat(ref->matchesReference(service,ref,"TPLTM_20260805",1990,"TXN-COMMERCE")));
        verify(registrationService).getByRegistrationNo("TPLTM_20260805");
        verifyNoMoreInteractions(registrationService);
        verifyNoInteractions(coopInviteService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void realSignedWrongAppidIsRejectedForAllFourRoutesAfterACorrectPositiveControl() throws Exception {
        KeyPair keys = generateKeyPair();
        Map<String, Integer> orders = Map.of("CLM_UNKNOWN", 100, "TPLTM_20260805", 1990,
                "DEP1T100", 5000, "REG20260725001", 1990);
        for (Map.Entry<String, Integer> order : orders.entrySet()) {
            WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
            ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
            ICoopInviteService coop = mock(ICoopInviteService.class);
            wireParser(service, buildParser(keys.getPublic()), registration, coop, mock(IRefundService.class));
            com.chengyinhub.business.service.IClubMembershipService membership =
                    mock(com.chengyinhub.business.service.IClubMembershipService.class);
            org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IClubMembershipService> clubs =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(clubs.getIfAvailable()).thenReturn(membership);
            WechatVerifiedFactTestSupport.setField(service, "clubMembershipServiceProvider", clubs);
            IMerchantCommerceService commerce = (IMerchantCommerceService)
                    WechatVerifiedFactTestSupport.getField(service, "merchantCommerceService");
            when(membership.supportsOutTradeNo("CLM_UNKNOWN")).thenReturn(true);
            when(membership.handlePaidInvoice(eq("CLM_UNKNOWN"), eq(true), eq(100), eq("TX1"),
                    argThat(ref -> matchesReference(service,ref,"CLM_UNKNOWN",100,"TX1")))).thenReturn(true);
            when(commerce.supportsOutTradeNo("TPLTM_20260805")).thenReturn(true);
            when(commerce.handlePaidOrder(eq("TPLTM_20260805"), eq(true), eq(1990), eq("TX1"),
                    argThat(ref -> matchesReference(service,ref,"TPLTM_20260805",1990,"TX1")))).thenReturn(true);
            when(coop.markDepositPaid(eq("DEP1T100"),eq(5000L),eq("TX1"),org.mockito.ArgumentMatchers.any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class)))
                .thenAnswer(call -> {
                    DepositVerifiedReferenceTestSupport.assertOriginal(service,call.getArgument(3),"DEP1T100",5000L,"TX1");
                    return true;
                });
            when(registration.handleWechatPayCallback(anyMap())).thenReturn("SUCCESS");
            com.wechat.pay.java.service.payments.jsapi.JsapiService querySdk =
                    (com.wechat.pay.java.service.payments.jsapi.JsapiService) WechatVerifiedFactTestSupport.getField(service, "jsapiService");
            org.mockito.Mockito.doReturn(originalOrder(order.getKey(), order.getValue(), "TX1", "SUCCESS"))
                    .when(querySdk).queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any());
            Forged positive = buildValidPayNotification(keys.getPrivate(), order.getKey(), "TX1", "SUCCESS", order.getValue());
            assertTrue(service.handlePayCallback(positive.body, headersOf(positive)).contains("SUCCESS"));
            org.mockito.Mockito.clearInvocations(registration, coop, membership, commerce);
            String wrongFacts = String.format("{\"out_trade_no\":\"%s\",\"transaction_id\":\"TX1\","
                    + "\"trade_state\":\"SUCCESS\",\"appid\":\"wx-other-app\",\"trade_type\":\"JSAPI\","
                    + "\"mchid\":\"merchant-test\",\"amount\":{\"total\":%d,\"currency\":\"CNY\"}}",
                    order.getKey(), order.getValue());
            Forged negative = sign(keys.getPrivate(), "transaction", "TRANSACTION.SUCCESS", wrongFacts);
            assertTrue(service.handlePayCallback(negative.body, headersOf(negative)).contains("FAIL"));
            verifyNoInteractions(registration, coop, membership, commerce);
            AlarmService alarm = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
            verify(alarm).alarmThrottled("payment.callback.facts", "支付回调原单事实缺失或不符",
                    "order=" + order.getKey() + " result=FAIL");
        }
    }

    @Test
    void handlePayCallbackWithForgedSignatureIsRejectedAndNeverTouchesBusinessLogic() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
        ICoopInviteService coopInviteService = mock(ICoopInviteService.class);
        wireParser(service, parser, registrationService, coopInviteService, mock(IRefundService.class));

        Forged n = buildValidPayNotification(kp.getPrivate(), "REG20260725001", "TXN123", "SUCCESS", 1990);
        byte[] sig = Base64.getDecoder().decode(n.signature);
        sig[0] ^= 0xFF;
        n.signature = Base64.getEncoder().encodeToString(sig);

        String resp = service.handlePayCallback(n.body, headersOf(n));

        assertTrue(resp.contains("FAIL"));
        assertTrue(resp.contains("签名验证失败"));
        // 核心断言:伪造回调必须在验签阶段被拦下,不能有任何一条路径先斩后奏碰到业务层。
        verifyNoInteractions(registrationService);
        verifyNoInteractions(coopInviteService);
        // 验签失败按固定事件限频告警，不回显未验签请求内容（订单号也不可信）。
        AlarmService alarms = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
        verify(alarms).alarmThrottled("payment.callback.signature", "支付回调验签失败",
                "type=ValidationException result=FAIL");
    }

    @Test
    void handleRefundCallbackBusinessFailureAlarmsWithVerifiedRefundNumber() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        IRefundService refundBizService = mock(IRefundService.class);
        wireParser(service, buildParser(kp.getPublic()), mock(ICmsRegistrationService.class),
                mock(ICoopInviteService.class), refundBizService);
        when(refundBizService.handleRefundNotify(org.mockito.ArgumentMatchers.anyMap())).thenReturn(false);

        Forged n = buildValidRefundNotification(kp.getPrivate(), "RF12345", "SUCCESS");
        String resp = service.handleRefundCallback(n.body, headersOf(n));

        assertTrue(resp.contains("FAIL"));
        AlarmService alarms = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
        verify(alarms).alarmThrottled("refund.callback.failed", "退款回调业务处理失败",
                "refund=RF12345 result=FAIL");
    }

    @Test
    void handleRefundCallbackWithValidSignatureInvokesRefundServiceExactlyOnce() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        IRefundService refundBizService = mock(IRefundService.class);
        wireParser(service, parser, mock(ICmsRegistrationService.class), mock(ICoopInviteService.class), refundBizService);
        when(refundBizService.handleRefundNotify(org.mockito.ArgumentMatchers.anyMap())).thenReturn(true);

        Forged n = buildValidRefundNotification(kp.getPrivate(), "RF12345", "SUCCESS");
        String resp = service.handleRefundCallback(n.body, headersOf(n));

        assertTrue(resp.contains("SUCCESS"));
        verify(refundBizService).handleRefundNotify(org.mockito.ArgumentMatchers.argThat(facts ->
                "RF12345".equals(facts.get("outRefundNo")) && "ORDER001".equals(facts.get("outTradeNo"))
                        && "SUCCESS".equals(facts.get("status")) && "1250".equals(facts.get("refund"))
                        && "3000".equals(facts.get("total")) && "CNY".equals(facts.get("currency"))));
    }

    @Test
    void handleRefundCallbackWithForgedSignatureIsRejectedAndNeverTouchesBusinessLogic() throws Exception {
        KeyPair kp = generateKeyPair();
        NotificationParser parser = buildParser(kp.getPublic());
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        IRefundService refundBizService = mock(IRefundService.class);
        wireParser(service, parser, mock(ICmsRegistrationService.class), mock(ICoopInviteService.class), refundBizService);

        Forged n = buildValidRefundNotification(kp.getPrivate(), "RF12345", "SUCCESS");
        n.body = n.body.replace("\"id\":\"evt-1\"", "\"id\":\"evt-1-tampered\"");

        String resp = service.handleRefundCallback(n.body, headersOf(n));

        assertTrue(resp.contains("FAIL"));
        assertTrue(resp.contains("签名验证失败"));
        verifyNoInteractions(refundBizService);
        AlarmService alarms = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
        verify(alarms).alarmThrottled("refund.callback.signature", "退款回调验签失败",
                "type=ValidationException result=FAIL");
    }

    @Test
    void depositPayRoutesVerifiedAmountAndRejectsUnpersistedResult() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICoopInviteService coop = mock(ICoopInviteService.class);
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        wireParser(service, buildParser(kp.getPublic()), registration, coop, mock(IRefundService.class));
        Forged n = buildValidPayNotification(kp.getPrivate(), "DEP1T100", "WXDEP1", "SUCCESS", 5000);
        assertTrue(service.handlePayCallback(n.body, headersOf(n)).contains("FAIL"));
        when(coop.markDepositPaid(eq("DEP1T100"),eq(5000L),eq("WXDEP1"),org.mockito.ArgumentMatchers.any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class)))
                .thenAnswer(call -> {
                    DepositVerifiedReferenceTestSupport.assertOriginal(service,call.getArgument(3),"DEP1T100",5000L,"WXDEP1");
                    return true;
                });
        assertTrue(service.handlePayCallback(n.body, headersOf(n)).contains("SUCCESS"));
        verify(registration, org.mockito.Mockito.times(2)).getByRegistrationNo("DEP1T100");
        verifyNoMoreInteractions(registration);
    }

    @Test
    void depositPaymentRejectsSignedWrongCurrencyOrMerchantBeforeBusiness() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICoopInviteService coop = mock(ICoopInviteService.class);
        when(coop.markDepositPaid(eq("DEP1T100"),eq(5000L),eq("WXDEP1"),org.mockito.ArgumentMatchers.any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class)))
                .thenAnswer(call -> {
                    DepositVerifiedReferenceTestSupport.assertOriginal(service,call.getArgument(3),"DEP1T100",5000L,"WXDEP1");
                    return true;
                });
        wireParser(service,buildParser(kp.getPublic()),mock(ICmsRegistrationService.class),coop,mock(IRefundService.class));
        for (String[] pair : new String[][]{{"USD","merchant-test"},{"CNY","other-merchant"},{"","merchant-test"}}) {
            String payload=String.format("{\"out_trade_no\":\"DEP1T100\",\"transaction_id\":\"WXDEP1\",\"trade_state\":\"SUCCESS\",\"appid\":\"wx-test-mini\",\"trade_type\":\"JSAPI\",\"mchid\":\"%s\",\"amount\":{\"total\":5000,\"currency\":\"%s\"}}",pair[1],pair[0]);
            Forged n=sign(kp.getPrivate(),"transaction","TRANSACTION.SUCCESS",payload);
            assertTrue(service.handlePayCallback(n.body,headersOf(n)).contains("FAIL"));
        }
        verifyNoInteractions(coop);
    }

    @Test
    void depositRefundRoutesCompleteVerifiedFactsAndRequiresBusinessAck() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICoopInviteService coop = mock(ICoopInviteService.class);
        IRefundService registrationRefund = mock(IRefundService.class);
        wireParser(service, buildParser(kp.getPublic()), mock(ICmsRegistrationService.class), coop, registrationRefund);
        Forged n = depositRefundNotification(kp.getPrivate());
        assertTrue(service.handleRefundCallback(n.body, headersOf(n)).contains("FAIL"));
        when(coop.handleDepositRefundResult(anyMap())).thenAnswer(call -> {
            Map<String, String> facts = call.getArgument(0);
            assertEquals("RDEP1T100", facts.get("outRefundNo"));
            assertEquals("DEP1T100", facts.get("outTradeNo"));
            assertEquals("5000", facts.get("refund"));
            assertEquals("5000", facts.get("total"));
            assertEquals("CNY", facts.get("currency"));
            assertEquals("SUCCESS", facts.get("status"));
            return true;
        });
        assertTrue(service.handleRefundCallback(n.body, headersOf(n)).contains("SUCCESS"));
        verifyNoInteractions(registrationRefund);
    }

    @Test
    void tamperedDepositRefundCannotReachEitherBusinessService() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICoopInviteService coop = mock(ICoopInviteService.class);
        IRefundService registrationRefund = mock(IRefundService.class);
        wireParser(service, buildParser(kp.getPublic()), mock(ICmsRegistrationService.class), coop, registrationRefund);
        Forged n = depositRefundNotification(kp.getPrivate());
        n.body = n.body.replace("evt-1", "tampered");
        assertTrue(service.handleRefundCallback(n.body, headersOf(n)).contains("FAIL"));
        verifyNoInteractions(coop, registrationRefund);
    }

    private static Forged depositRefundNotification(PrivateKey key) throws Exception {
        return sign(key, "refund.gzh", "REFUND.SUCCESS",
                "{\"out_refund_no\":\"RDEP1T100\",\"out_trade_no\":\"DEP1T100\","
                + "\"transaction_id\":\"WXDEP1\",\"success_time\":\"2026-10-24T00:00:00Z\",\"refund_id\":\"WXREF1\",\"refund_status\":\"SUCCESS\","
                + "\"amount\":{\"refund\":5000,\"total\":5000,\"currency\":\"CNY\"}}");
    }

    @Test
    void verifiedUnsupportedMembershipIsAlarmedAndStillFailsWithoutRegistrationFallback() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        wireParser(service, buildParser(kp.getPublic()), registration, mock(ICoopInviteService.class), mock(IRefundService.class));
        AlarmService alarm = mock(AlarmService.class);
        WechatVerifiedFactTestSupport.setField(service, "alarmService", alarm);
        Forged notification = buildValidPayNotification(kp.getPrivate(), "CLM_UNKNOWN", "WX_TEST", "SUCCESS", 100);
        assertTrue(service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verify(alarm).alarmThrottled(org.mockito.ArgumentMatchers.eq("payment.callback.club-membership"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.contains("order=CLM_UNKNOWN"));
        verify(registration).getByRegistrationNo("CLM_UNKNOWN");
        verifyNoMoreInteractions(registration);
    }

    @Test
    void verifiedMissingRegistrationReportsItsRealOrderOnceAndDoesNotCreateInboxOrPaymentEffects() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        CmsRegistrationServiceImpl registration = new CmsRegistrationServiceImpl();
        com.chengyinhub.business.mapper.CmsRegistrationMapper mapper = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        WechatVerifiedFactTestSupport.setField(registration, "cmsRegistrationMapper", mapper);
        WechatVerifiedFactTestSupport.setField(registration, "alarmService", alarm);
        wireParser(service, buildParser(kp.getPublic()), registration, mock(ICoopInviteService.class), mock(IRefundService.class));
        WechatVerifiedFactTestSupport.setField(service, "alarmService", alarm);
        Forged notification = buildValidPayNotification(kp.getPrivate(), "REG_MISSING", "WX_TEST", "SUCCESS", 100);
        assertTrue(service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verify(mapper).selectByRegistrationNo("REG_MISSING");
        verify(alarm).alarmThrottled(org.mockito.ArgumentMatchers.eq("payment.callback.routing"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("order=REG_MISSING matches=0 result=FAIL"));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper, alarm);
    }

    @Test
    void registrationCallbackWithMissingPayloadOrFieldsAlertsInsteadOfFailingSilently() {
        CmsRegistrationServiceImpl registration = new CmsRegistrationServiceImpl();
        com.chengyinhub.business.mapper.CmsRegistrationMapper mapper = mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);
        AlarmService alarm = mock(AlarmService.class);
        WechatVerifiedFactTestSupport.setField(registration, "cmsRegistrationMapper", mapper);
        WechatVerifiedFactTestSupport.setField(registration, "alarmService", alarm);
        assertTrue("FAIL".equals(registration.handleWechatPayCallback(null)));
        java.util.Map<String, String> partial = new java.util.HashMap<>();
        partial.put("out_trade_no", "REG_PARTIAL");
        assertTrue("FAIL".equals(registration.handleWechatPayCallback(partial)));
        verify(alarm).alarmThrottled(org.mockito.ArgumentMatchers.eq("payment.callback.registration"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.contains("reason=payload_missing"));
        verify(alarm).alarmThrottled(org.mockito.ArgumentMatchers.eq("payment.callback.registration"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.contains("order=REG_PARTIAL reason=required_field_missing"));
        verifyNoInteractions(mapper);
    }

    @Test
    void verifiedRegistrationAmountMismatchAlertsOnceAndKeepsFailWithoutPaymentEffects() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_AMOUNT");
        assertTrue(fixture.callback("SUCCESS", 99).contains("FAIL"));
        fixture.assertFactsAlarm("REG_AMOUNT");
        verifyNoInteractions(fixture.mapper);
    }

    @Test
    void verifiedRegistrationRefundConflictAlertsOnceAndKeepsFail() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_REFUND");
        fixture.registration.setPointUsed(100L);
        fixture.registration.setPointsReturned(0);
        assertTrue(fixture.callback("REFUND", 100).contains("FAIL"));
        // Old claim/refund_state_conflict expectations predated A's verified-original-refund rule:
        // a payment REFUND state has no original refundNo/refundId or independent REFUND SUCCESS.
        fixture.assertAlarm("REG_REFUND", "refund_requires_verified_original_refund");
        verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_REFUND");
        fixture.assertOriginalScopeRead();
        verify(fixture.mapper, org.mockito.Mockito.never()).claimRegistrationRefundedByGateway(
                org.mockito.ArgumentMatchers.eq(17L), org.mockito.ArgumentMatchers.eq("system"), any());
        verify(fixture.mapper, org.mockito.Mockito.never()).claimPointsReturn(
                org.mockito.ArgumentMatchers.eq(17L), org.mockito.ArgumentMatchers.eq("system"), any());
        verify(fixture.mapper, org.mockito.Mockito.never()).claimPaymentSuccess(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any());
        verify(fixture.mapper, org.mockito.Mockito.never()).selectCmsRegistrationByIdForUpdate(17L);
        assertEquals(Long.valueOf(100L), fixture.registration.getPointUsed());
        assertEquals(Integer.valueOf(0), fixture.registration.getPointsReturned());
        fixture.assertNoLocalPaymentEffects();
        verifyNoInteractions(fixture.cashAdmission);
        verifyNoMoreInteractions(fixture.mapper);
    }

    @Test
    void verifiedRegistrationPaymentConflictAlertsOnceAndKeepsFail() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_PAYMENT");
        when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
        assertTrue(fixture.callback("SUCCESS", 100).contains("FAIL"));
        fixture.assertAlarm("REG_PAYMENT", "payment_state_conflict");
        verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_PAYMENT");
        verify(fixture.mapper).claimPaymentSuccess(org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq("WX_PRIVATE_TRANSACTION"), org.mockito.ArgumentMatchers.any(java.util.Date.class));
        verify(fixture.mapper).selectCmsRegistrationByIdForUpdate(17L);
        fixture.assertOriginalScopeRead();
        verifyNoMoreInteractions(fixture.mapper);
    }

    @Test
    void verifiedPaymentConflictReachesOriginalCasAndRetainsItsFactWithoutLocalPaymentEffects() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_PAYMENT");
        when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
        assertTrue(fixture.callback("SUCCESS", 100).contains("FAIL"));
        var proof = fixture.verifiedPayment();
        org.junit.jupiter.api.Assertions.assertSame(fixture.verifiedFacts, WechatVerifiedFactTestSupport.getField(fixture.registrationService, "verifiedPaymentFacts"));
        fixture.assertAlarm("REG_PAYMENT", "payment_state_conflict");
        org.mockito.InOrder steps = org.mockito.Mockito.inOrder(fixture.inbox, fixture.topics, fixture.mapper, fixture.cashAdmission);
        steps.verify(fixture.inbox).registerVerified("PAY:WX_PRIVATE_TRANSACTION:SUCCESS", 1, 17L, "SUCCESS", proof.reference());
        steps.verify(fixture.inbox).processVerified(org.mockito.ArgumentMatchers.eq("PAY:WX_PRIVATE_TRANSACTION:SUCCESS"),
                org.mockito.ArgumentMatchers.eq(proof.reference()), any());
        steps.verify(fixture.topics).selectCmsTopicByIdForUpdate(RegistrationFailureFixture.TOPIC_ID);
        steps.verify(fixture.mapper).selectByParentOrderNoForUpdate(RegistrationFailureFixture.PARENT_ORDER, null);
        steps.verify(fixture.cashAdmission).requirePaidHalfStateCashAdmission(17L, proof.reference());
        steps.verify(fixture.mapper).claimPaymentSuccess(org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq("WX_PRIVATE_TRANSACTION"), org.mockito.ArgumentMatchers.any(java.util.Date.class));
        steps.verify(fixture.mapper).selectCmsRegistrationByIdForUpdate(17L);
        steps.verify(fixture.cashAdmission).retainClosedPaidHalfStateSuccess(17L, proof.reference());
        fixture.assertNoLocalPaymentEffects();
    }

    @Test
    void verifiedRegistrationCloseConflictAndDisappearedOrderEachAlertOnceAndKeepFail() throws Exception {
        for (boolean disappeared : new boolean[]{false, true}) {
            RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_CLOSED");
            if (!disappeared) {
                when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
            }
            assertTrue(fixture.callback("CLOSED", 100).contains("FAIL"));
            fixture.assertAlarm("REG_CLOSED", disappeared ? "order_disappeared" : "failure_state_conflict");
            verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_CLOSED");
            verify(fixture.mapper).claimPaymentFailure(org.mockito.ArgumentMatchers.eq(17L),
                    org.mockito.ArgumentMatchers.any(java.util.Date.class));
            verify(fixture.mapper).selectCmsRegistrationByIdForUpdate(17L);
            fixture.assertOriginalScopeRead();
            verifyNoMoreInteractions(fixture.mapper);
        }
    }

    @Test
    void verifiedRegistrationUnsafeReferenceIsRedactedFromFailureAlarm() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("private-order@example.com");
        assertTrue(fixture.callback("SUCCESS", 99).contains("FAIL"));
        fixture.assertFactsAlarm("UNKNOWN");
        verifyNoInteractions(fixture.mapper);
    }

    @Test
    void verifiedOldClosedNotificationCannotMutateANewRegistrationAttempt() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_CLOSED");
        var intents = (WechatPaymentIntentTestSupport)WechatVerifiedFactTestSupport.getField(fixture.service, "paymentIntentJdbc");
        // Negative storage fixture: the real shared guard must reject an older observed provider number.
        intents.intents.put("merchant-test:WXP_CURRENT", new java.util.HashMap<>(Map.of(
                "mchid","merchant-test","business_out_trade_no","REG_CLOSED","out_trade_no","WXP_CURRENT",
                "biz_type","REGISTRATION","biz_id",17L,"attempt_no",2)));
        assertTrue(fixture.callback("CLOSED", 100).contains("FAIL"));
        fixture.assertAlarm("REG_CLOSED", "payment_attempt_conflict");
        verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_CLOSED");
        fixture.assertOriginalScopeRead();
        verifyNoMoreInteractions(fixture.mapper);
    }

    @Test
    void verifiedRegistrationAlreadyClosedAcknowledgesSuccessWithoutFailureAlarm() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_CLOSED");
        fixture.registration.setPaymentStatus(3);
        fixture.registration.setRegistrationStatus(1);
        when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
        assertTrue(fixture.callback("CLOSED", 100).contains("SUCCESS"));
        fixture.assertOriginalScopeRead();
        verifyNoInteractions(fixture.alarm);
    }

    @Test
    void forgedExistingRegistrationFailureCannotTriggerLookupOrAlarm() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_AMOUNT");
        Forged notification = buildValidPayNotification(fixture.keys.getPrivate(), fixture.order,
                "WX_PRIVATE_TRANSACTION", "SUCCESS", 99);
        byte[] signature = Base64.getDecoder().decode(notification.signature);
        signature[0] ^= 0xff;
        notification.signature = Base64.getEncoder().encodeToString(signature);
        assertTrue(fixture.service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verifyNoInteractions(fixture.mapper);
        // 验签失败只允许固定、限频、不含任何请求内容的运维事件（平台证书轮换/配置错误时所有回调都会落到这里）；
        // 不得出现带订单号的业务告警。
        verify(fixture.alarm).alarmThrottled("payment.callback.signature", "支付回调验签失败",
                "type=ValidationException result=FAIL");
        verifyNoMoreInteractions(fixture.alarm);
    }

    @Test
    void verifiedBusinessExceptionIsAlarmedOnceAndKeepsFailWithOnlySafeOrderAndType() throws Exception {
        KeyPair keys = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        wireParser(service, buildParser(keys.getPublic()), registration,
                mock(ICoopInviteService.class), mock(IRefundService.class));
        AlarmService alarm = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
        when(registration.handleWechatPayCallback(anyMap()))
                .thenThrow(new IllegalStateException("private financial failure detail"));
        Forged notification = buildValidPayNotification(keys.getPrivate(), "REG_EXCEPTION",
                "WX_PRIVATE_TRANSACTION", "SUCCESS", 100);
        assertTrue(service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verify(registration).handleWechatPayCallback(anyMap());
        verify(alarm).alarmThrottled("payment.callback.business_exception", "支付回调业务处理异常",
                "order=REG_EXCEPTION type=IllegalStateException result=FAIL");
        verifyNoMoreInteractions(alarm);
    }

    @Test
    void signedButUnparseableNotificationCannotTriggerBusinessLookupOrAlarm() throws Exception {
        KeyPair keys = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        wireParser(service, buildParser(keys.getPublic()), registration,
                mock(ICoopInviteService.class), mock(IRefundService.class));
        AlarmService alarm = (AlarmService) WechatVerifiedFactTestSupport.getField(service, "alarmService");
        Forged notification = sign(keys.getPrivate(), "transaction", "TRANSACTION.SUCCESS", "{invalid_json");
        assertTrue(service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verifyNoInteractions(registration, alarm);
    }

    @Test
    void forgedUnknownOrderCannotTriggerABusinessAlarm() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICmsRegistrationService registration = mock(ICmsRegistrationService.class);
        wireParser(service, buildParser(kp.getPublic()), registration, mock(ICoopInviteService.class), mock(IRefundService.class));
        AlarmService alarm = mock(AlarmService.class);
        WechatVerifiedFactTestSupport.setField(service, "alarmService", alarm);
        Forged notification = buildValidPayNotification(kp.getPrivate(), "CLM_UNKNOWN", "WX_TEST", "SUCCESS", 100);
        byte[] signature = Base64.getDecoder().decode(notification.signature); signature[0] ^= 0xff;
        notification.signature = Base64.getEncoder().encodeToString(signature);
        assertTrue(service.handlePayCallback(notification.body, headersOf(notification)).contains("FAIL"));
        verifyNoInteractions(registration);
        verify(alarm).alarmThrottled("payment.callback.signature", "支付回调验签失败",
                "type=ValidationException result=FAIL");
        verifyNoMoreInteractions(alarm);
    }

    private static class RegistrationFailureFixture {
        static final long TOPIC_ID = 7001L;
        static final String PARENT_ORDER = "SIGNATURE_FIXTURE_PARENT";
        final KeyPair keys = generateKeyPair();
        final WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        final CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        final AlarmService alarm = mock(AlarmService.class);
        final CmsRegistration registration = new CmsRegistration();
        final com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        final com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper consumed = mock(com.chengyinhub.business.mapper.CmsRegistrationPaymentFactMapper.class);
        final com.chengyinhub.business.service.ISettlementEntryService settlements = mock(com.chengyinhub.business.service.ISettlementEntryService.class);
        final com.chengyinhub.business.mapper.UmsMemberMapper members = mock(com.chengyinhub.business.mapper.UmsMemberMapper.class);
        final com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper pointLedger = mock(com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.class);
        final IRefundService refunds = mock(IRefundService.class);
        // This unit fixture tests A's conflict path, not G's actual cash admission/recovery persistence.
        final com.chengyinhub.business.service.ICoopSettlementClaimService cashAdmission = mock(com.chengyinhub.business.service.ICoopSettlementClaimService.class);
        final CmsRegistrationServiceImpl registrationService;
        final WechatVerifiedPaymentFactService verifiedFacts;
        final IWxPaymentCallbackInboxService inbox;
        final String order;

        RegistrationFailureFixture(String order) throws Exception {
            this.order = order;
            registration.setId(17L);
            registration.setRegistrationNo(order);
            registration.setMemberId(1L);
            registration.setOwnerType(1L);
            registration.setOwnerId(TOPIC_ID);
            registration.setTopicId(TOPIC_ID);
            registration.setParentOrderNo(PARENT_ORDER);
            registration.setPaymentStatus(1);
            registration.setRegistrationStatus(1);
            registration.setWechatPaymentAmount(new BigDecimal("1.00"));
            registrationService = new CmsRegistrationServiceImpl();
            WechatVerifiedFactTestSupport.setField(registrationService, "cmsRegistrationMapper", mapper);
            WechatVerifiedFactTestSupport.setField(registrationService, "cmsTopicMapper", topics);
            WechatVerifiedFactTestSupport.setField(registrationService, "registrationPaymentFactMapper", consumed);
            WechatVerifiedFactTestSupport.setField(registrationService, "settlementEntryService", settlements);
            WechatVerifiedFactTestSupport.setField(registrationService, "umsMemberMapper", members);
            WechatVerifiedFactTestSupport.setField(registrationService, "umsMemberPointsDetailMapper", pointLedger);
            WechatVerifiedFactTestSupport.setField(registrationService, "coopSettlementClaimService", cashAdmission);
            when(cashAdmission.retainClosedPaidHalfStateSuccess(any(), any())).thenReturn(true);
            var topic = new com.chengyinhub.business.domain.CmsTopic();
            topic.setId(TOPIC_ID); topic.setIsSettlement(0);
            when(topics.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
            when(mapper.selectByParentOrderNoForUpdate(PARENT_ORDER, null)).thenReturn(java.util.List.of(registration));
            WechatVerifiedFactTestSupport.setField(registrationService, "alarmService", alarm);
            org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IWechatPayService> attempts =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(attempts.getObject()).thenReturn(service);
            WechatVerifiedFactTestSupport.setField(registrationService, "paymentAttemptGateway", attempts);
            when(mapper.selectByRegistrationNo(order)).thenReturn(registration);
            wireParser(service, buildParser(keys.getPublic()), registrationService,
                    mock(ICoopInviteService.class), refunds);
            // wireParser installs B's final SDK/intent bindings before capturing that same real reader.
            verifiedFacts = (WechatVerifiedPaymentFactService) WechatVerifiedFactTestSupport.getField(service, "verifiedFacts");
            org.junit.jupiter.api.Assertions.assertNotNull(verifiedFacts, "reuse B's actual SDK original-fact reader after its final binding");
            // Same declared original business row for the writer and the real shared attempt guard.
            com.chengyinhub.business.service.WechatPaymentOrderOwner originalOwner = businessNo -> {
                if (!order.equals(businessNo))
                    throw new com.chengyinhub.business.service.WechatPaymentOrderOwner.MissingOrderException();
                return new com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot(
                        "REGISTRATION",registration.getId(),registration.getWechatPaymentAmount(),false,
                        Integer.valueOf(1).equals(registration.getPaymentStatus())
                                && Integer.valueOf(1).equals(registration.getRegistrationStatus()));
            };
            WechatVerifiedFactTestSupport.setField(verifiedFacts, "factBusinessOwner", originalOwner);
            WechatVerifiedFactTestSupport.setField(WechatVerifiedFactTestSupport.getField(service,"intents"),
                    "businessOwner", originalOwner);
            WechatVerifiedFactTestSupport.setField(registrationService, "verifiedPaymentFacts", verifiedFacts);
            inbox = com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough(
                    WechatVerifiedFactTestSupport.unitBusinessTransactions(), verifiedFacts);
            WechatVerifiedFactTestSupport.setField(registrationService, "wxPaymentCallbackInboxService", inbox);
            WechatVerifiedFactTestSupport.setField(service, "alarmService", alarm);
        }

        WechatVerifiedPaymentFactService.Stored verifiedPayment() {
            var stored = verifiedFacts.findPaymentSuccess("merchant-test", order, "WX_PRIVATE_TRANSACTION");
            org.junit.jupiter.api.Assertions.assertNotNull(stored, "the actual gateway must have persisted the original verified payment fact");
            assertEquals(order, stored.facts().businessOutTradeNo());
            assertEquals(100L, stored.facts().totalFen());
            return stored;
        }

        void assertOriginalScopeRead() {
            verify(topics).selectCmsTopicByIdForUpdate(TOPIC_ID);
            verify(mapper).selectByParentOrderNoForUpdate(PARENT_ORDER, null);
        }

        void assertNoLocalPaymentEffects() {
            assertEquals(Integer.valueOf(1), registration.getPaymentStatus());
            assertEquals(Integer.valueOf(1), registration.getRegistrationStatus());
            assertEquals(new BigDecimal("1.00"), registration.getWechatPaymentAmount());
            verifyNoInteractions(consumed, settlements, members, pointLedger, refunds);
            verify(mapper, org.mockito.Mockito.never()).claimPaymentFailure(org.mockito.ArgumentMatchers.anyLong(), any());
            verify(mapper, org.mockito.Mockito.never()).claimRegistrationRefundedByGateway(
                    org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any());
        }

        String callback(String state, int cents) throws Exception {
            com.wechat.pay.java.service.payments.jsapi.JsapiService querySdk =
                    (com.wechat.pay.java.service.payments.jsapi.JsapiService) WechatVerifiedFactTestSupport.getField(service, "jsapiService");
            org.mockito.Mockito.doReturn(originalOrder(order, 100, "WX_PRIVATE_TRANSACTION", state))
                    .when(querySdk).queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any());
            Forged notification = buildValidPayNotification(keys.getPrivate(), order,
                    "WX_PRIVATE_TRANSACTION", state, cents);
            return service.handlePayCallback(notification.body, headersOf(notification));
        }

        void assertAlarm(String reference, String reason) {
            verify(alarm).alarmThrottled("payment.callback.registration", "支付回调报名处理失败",
                    "order=" + reference + " reason=" + reason + " result=FAIL");
            verifyNoMoreInteractions(alarm);
        }

        void assertFactsAlarm(String reference) {
            verify(alarm).alarmThrottled("payment.callback.facts", "支付回调原单事实缺失或不符",
                    "order=" + reference + " result=FAIL");
            verifyNoMoreInteractions(alarm);
        }
    }

    // ---------- 构造辅助:自建密钥对 + 手写符合 SDK 期望格式的签名报文/加密资源 ----------

    @SuppressWarnings("unchecked")
    private static void wireParser(WechatPayServiceImpl service, NotificationParser parser,
                                    ICmsRegistrationService registrationService,
                                    ICoopInviteService coopInviteService,
                                    IRefundService refundBizService) {
        WechatPaymentIntentTestSupport.install(service, Map.of());
        WechatVerifiedFactTestSupport.setField(service, "notificationParser", parser);
        com.chengyinhub.common.config.WxPayConfig config=mock(com.chengyinhub.common.config.WxPayConfig.class);
        when(config.getMerchantId()).thenReturn("merchant-test");
        WechatVerifiedFactTestSupport.setField(service,"wxPayConfig",config);
        // Independent original-order query fixtures, not facts copied from the incoming notification.
        Map<String, Integer> frozenAmounts = Map.ofEntries(
                Map.entry("ORDER001",3000), Map.entry("REG20260725001", 1990), Map.entry("TPLTM_20260805", 1990),
                Map.entry("DEP1T100", 5000), Map.entry("CLM_UNKNOWN", 100), Map.entry("REG_MISSING", 100),
                Map.entry("REG_AMOUNT", 100), Map.entry("REG_REFUND", 100), Map.entry("REG_PAYMENT", 100),
                Map.entry("REG_CLOSED", 100), Map.entry("REG_EXCEPTION", 100));
        Map<String, String> transactionIds = Map.of("REG20260725001", "TXN123", "TPLTM_20260805", "TXN-COMMERCE",
                "ORDER001", "TX-ORDER001", "DEP1T100", "WXDEP1", "CLM_UNKNOWN", "WX_TEST", "REG_MISSING", "WX_TEST");
        com.wechat.pay.java.service.payments.jsapi.JsapiService querySdk =
                mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
        when(querySdk.queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest query = call.getArgument(0);
            String order = query.getOutTradeNo();
            return frozenAmounts.containsKey(order) ? originalOrder(order, frozenAmounts.get(order),
                    transactionIds.getOrDefault(order, "WX_PRIVATE_TRANSACTION"), "SUCCESS") : null;
        });
        if (org.mockito.Mockito.mockingDetails(registrationService).isMock()) {
            for (String existingNo : new String[]{"REG20260725001", "REG_EXCEPTION"}) {
                com.chengyinhub.business.domain.CmsRegistration existing = new com.chengyinhub.business.domain.CmsRegistration();
                existing.setRegistrationNo(existingNo);
                org.mockito.Mockito.lenient().when(registrationService.getByRegistrationNo(existingNo)).thenReturn(existing);
            }
        }
        var refundQuerySdk = mock(com.wechat.pay.java.service.refund.RefundService.class);
        when(refundQuerySdk.queryByOutRefundNo(any())).thenAnswer(call -> {
            var request = (com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest)call.getArgument(0);
            boolean deposit = "RDEP1T100".equals(request.getOutRefundNo());
            if (!deposit && !"RF12345".equals(request.getOutRefundNo())) return null;
            var provider = new com.wechat.pay.java.service.refund.model.Refund();
            provider.setOutRefundNo(request.getOutRefundNo()); provider.setOutTradeNo(deposit ? "DEP1T100" : "ORDER001");
            provider.setRefundId(deposit ? "WXREF1" : "WXREF001"); provider.setTransactionId(deposit ? "WXDEP1" : "TX-ORDER001");
            provider.setStatus(com.wechat.pay.java.service.refund.model.Status.SUCCESS);
            provider.setSuccessTime(WechatVerifiedFactTestSupport.SUCCESS_TIME);
            var amount = new com.wechat.pay.java.service.refund.model.Amount();
            amount.setTotal(deposit ? 5000L : 3000L); amount.setRefund(deposit ? 5000L : 1250L); amount.setCurrency("CNY");
            provider.setAmount(amount); return provider;
        });
        WechatVerifiedFactTestSupport.setField(service,"refundService",refundQuerySdk);
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", querySdk);
        WechatVerifiedFactTestSupport.setField(service, "registrationService", registrationService);
        WechatVerifiedFactTestSupport.setField(service, "coopInviteService", coopInviteService);
        WechatVerifiedFactTestSupport.setField(service, "refundBizService", refundBizService);
        WechatVerifiedFactTestSupport.setField(service, "merchantCommerceService", mock(IMerchantCommerceService.class));
        WechatVerifiedFactTestSupport.setField(service, "alarmService", mock(AlarmService.class));
    }

    private static Map<String, String> headersOf(Forged n) {
        return Map.of(
                "wechatpay-serial", n.serial,
                "wechatpay-nonce", n.headerNonce,
                "wechatpay-signature", n.signature,
                "wechatpay-timestamp", n.timestamp
        );
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static NotificationParser buildParser(PublicKey publicKey) {
        NotificationConfig config = new RSAPublicKeyNotificationConfig.Builder()
                .publicKey(publicKey)
                .publicKeyId(PUBLIC_KEY_ID)
                .apiV3Key(API_V3_KEY)
                .build();
        return new NotificationParser(config);
    }

    private static RequestParam toRequestParam(Forged n) {
        return new RequestParam.Builder()
                .serialNumber(n.serial)
                .nonce(n.headerNonce)
                .signature(n.signature)
                .timestamp(n.timestamp)
                .body(n.body)
                .build();
    }

    private static Forged buildValidPayNotification(PrivateKey signingKey, String outTradeNo,
                                                      String transactionId, String tradeState, int totalCents) throws Exception {
        String plaintext = String.format(
                "{\"out_trade_no\":\"%s\",\"transaction_id\":\"%s\",\"trade_state\":\"%s\",\"appid\":\"wx-test-mini\",\"trade_type\":\"JSAPI\",\"mchid\":\"merchant-test\",\"success_time\":\"2026-10-24T00:00:00Z\",\"amount\":{\"total\":%d,\"currency\":\"CNY\"}}",
                outTradeNo, transactionId, tradeState, totalCents);
        return sign(signingKey, "transaction", "TRANSACTION.SUCCESS", plaintext);
    }

    private static Transaction originalOrder(String order, int cents, String transactionId, String state) {
        Transaction original = new Transaction();
        original.setOutTradeNo(order);
        original.setTransactionId(transactionId);
        original.setSuccessTime("2026-10-24T00:00:00Z");
        original.setAppid("wx-test-mini");
        original.setMchid("merchant-test");
        original.setTradeType(Transaction.TradeTypeEnum.JSAPI);
        original.setTradeState(Transaction.TradeStateEnum.valueOf(state));
        com.wechat.pay.java.service.payments.model.TransactionAmount amount =
                new com.wechat.pay.java.service.payments.model.TransactionAmount();
        amount.setTotal(cents);
        amount.setCurrency("CNY");
        original.setAmount(amount);
        return original;
    }

    private static Forged buildValidRefundNotification(PrivateKey signingKey, String outRefundNo,
                                                         String refundStatus) throws Exception {
        String plaintext = String.format(
                "{\"out_refund_no\":\"%s\",\"out_trade_no\":\"ORDER001\",\"transaction_id\":\"TX-ORDER001\",\"success_time\":\"2026-10-24T00:00:00Z\",\"refund_id\":\"WXREF001\",\"refund_status\":\"%s\",\"amount\":{\"refund\":1250,\"total\":3000,\"currency\":\"CNY\"}}",
                outRefundNo, refundStatus);
        return sign(signingKey, "refund.gzh", "REFUND.SUCCESS", plaintext);
    }

    private static Forged sign(PrivateKey signingKey, String associatedData, String eventType, String plaintext) throws Exception {
        byte[] nonceBytes = new byte[12];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().withoutPadding().encodeToString(nonceBytes).substring(0, 16);

        AeadAesCipher cipher = new AeadAesCipher(API_V3_KEY.getBytes(StandardCharsets.UTF_8));
        String ciphertext = cipher.encrypt(
                associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                plaintext.getBytes(StandardCharsets.UTF_8));

        String body = String.format(
                "{\"id\":\"evt-1\",\"create_time\":\"2026-07-25T10:00:00+08:00\",\"event_type\":\"%s\"," +
                        "\"resource_type\":\"encrypt-resource\",\"summary\":\"test\",\"resource\":{" +
                        "\"original_type\":\"transaction\",\"algorithm\":\"AEAD_AES_256_GCM\"," +
                        "\"ciphertext\":\"%s\",\"associated_data\":\"%s\",\"nonce\":\"%s\"}}",
                eventType, ciphertext, associatedData, nonce);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String headerNonce = UUID.randomUUID().toString().replace("-", "");
        String message = timestamp + "\n" + headerNonce + "\n" + body + "\n";

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signingKey);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        Forged n = new Forged();
        n.body = body;
        n.timestamp = timestamp;
        n.headerNonce = headerNonce;
        n.signature = signature;
        n.serial = PUBLIC_KEY_ID;
        return n;
    }

    /** 可变的“已构造回调”容器,负控用例在构造完合法报文后单独篡改某一个字段。 */
    private static final class Forged {
        String body;
        String timestamp;
        String headerNonce;
        String signature;
        String serial;
    }
    private static boolean matchesReference(WechatPayServiceImpl service,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference ref,String no,long fen,String tx) {
        if(ref==null)return false;
        var reader=(WechatVerifiedPaymentFactService)WechatVerifiedFactTestSupport.getField(service,"verifiedFacts");
        var stored=reader.requireVerified(ref);var fact=stored.facts();
        return stored.reference().equals(ref) && ref.hash().equals(fact.factHash())
                && fact.kind()==com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT
                && no.equals(fact.businessOutTradeNo()) && fen==fact.totalFen() && tx.equals(fact.transactionId());
    }
}
