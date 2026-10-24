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
        when(commerceService.handlePaidOrder("TPLTM_20260805", true, 1990, "TXN-COMMERCE"))
                .thenReturn(true);

        Forged n = buildValidPayNotification(kp.getPrivate(), "TPLTM_20260805", "TXN-COMMERCE", "SUCCESS", 1990);
        String resp = service.handlePayCallback(n.body, headersOf(n));

        assertTrue(resp.contains("SUCCESS"));
        verify(commerceService).handlePaidOrder("TPLTM_20260805", true, 1990, "TXN-COMMERCE");
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
            when(membership.handlePaidInvoice("CLM_UNKNOWN", true, 100, "TX1")).thenReturn(true);
            when(commerce.supportsOutTradeNo("TPLTM_20260805")).thenReturn(true);
            when(commerce.handlePaidOrder("TPLTM_20260805", true, 1990, "TX1")).thenReturn(true);
            when(coop.markDepositPaid("DEP1T100", 5000L, "TX1")).thenReturn(true);
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
        when(coop.markDepositPaid("DEP1T100", 5000L, "WXDEP1")).thenReturn(true);
        assertTrue(service.handlePayCallback(n.body, headersOf(n)).contains("SUCCESS"));
        verify(registration, org.mockito.Mockito.times(2)).getByRegistrationNo("DEP1T100");
        verifyNoMoreInteractions(registration);
    }

    @Test
    void depositPaymentRejectsSignedWrongCurrencyOrMerchantBeforeBusiness() throws Exception {
        KeyPair kp = generateKeyPair();
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        ICoopInviteService coop = mock(ICoopInviteService.class);
        when(coop.markDepositPaid("DEP1T100",5000L,"WXDEP1")).thenReturn(true);
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
        when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
        assertTrue(fixture.callback("REFUND", 100).contains("FAIL"));
        fixture.assertAlarm("REG_REFUND", "refund_state_conflict");
        verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_REFUND");
        verify(fixture.mapper).claimRegistrationRefundedByGateway(org.mockito.ArgumentMatchers.eq(17L),
                org.mockito.ArgumentMatchers.eq("system"), org.mockito.ArgumentMatchers.any(java.util.Date.class));
        verify(fixture.mapper).selectCmsRegistrationByIdForUpdate(17L);
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
        verifyNoMoreInteractions(fixture.mapper);
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
        var registrationService = WechatVerifiedFactTestSupport.getField(fixture.service, "registrationService");
        var provider = (org.springframework.beans.factory.ObjectProvider<?>)WechatVerifiedFactTestSupport.getField(
                registrationService, "paymentAttemptGateway");
        var gateway = (com.chengyinhub.business.service.IWechatPayService)provider.getObject();
        when(gateway.lockPaymentAttemptForUpdate("REG_CLOSED", "REG_CLOSED")).thenReturn(false);
        assertTrue(fixture.callback("CLOSED", 100).contains("FAIL"));
        fixture.assertAlarm("REG_CLOSED", "payment_attempt_conflict");
        verify(fixture.mapper, org.mockito.Mockito.times(2)).selectByRegistrationNo("REG_CLOSED");
        verifyNoMoreInteractions(fixture.mapper);
    }

    @Test
    void verifiedRegistrationAlreadyClosedAcknowledgesSuccessWithoutFailureAlarm() throws Exception {
        RegistrationFailureFixture fixture = new RegistrationFailureFixture("REG_CLOSED");
        fixture.registration.setPaymentStatus(3);
        fixture.registration.setRegistrationStatus(1);
        when(fixture.mapper.selectCmsRegistrationByIdForUpdate(17L)).thenReturn(fixture.registration);
        assertTrue(fixture.callback("CLOSED", 100).contains("SUCCESS"));
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
        final KeyPair keys = generateKeyPair();
        final WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        final CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        final AlarmService alarm = mock(AlarmService.class);
        final CmsRegistration registration = new CmsRegistration();
        final String order;

        RegistrationFailureFixture(String order) throws Exception {
            this.order = order;
            registration.setId(17L);
            registration.setRegistrationNo(order);
            registration.setPaymentStatus(1);
            registration.setRegistrationStatus(1);
            registration.setWechatPaymentAmount(new BigDecimal("1.00"));
            CmsRegistrationServiceImpl registrationService = new CmsRegistrationServiceImpl();
            WechatVerifiedFactTestSupport.setField(registrationService, "cmsRegistrationMapper", mapper);
            WechatVerifiedFactTestSupport.setField(registrationService, "alarmService", alarm);
            var attemptGateway = mock(com.chengyinhub.business.service.IWechatPayService.class);
            when(attemptGateway.lockPaymentAttemptForUpdate(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
            org.springframework.beans.factory.ObjectProvider<com.chengyinhub.business.service.IWechatPayService> attempts =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(attempts.getObject()).thenReturn(attemptGateway);
            WechatVerifiedFactTestSupport.setField(registrationService, "paymentAttemptGateway", attempts);
            WechatVerifiedFactTestSupport.setField(registrationService, "wxPaymentCallbackInboxService",
                    com.chengyinhub.business.service.PaymentCallbackInboxStubs.passThrough());
            when(mapper.selectByRegistrationNo(order)).thenReturn(registration);
            wireParser(service, buildParser(keys.getPublic()), registrationService,
                    mock(ICoopInviteService.class), mock(IRefundService.class));
            WechatVerifiedFactTestSupport.setField(service, "alarmService", alarm);
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
}
