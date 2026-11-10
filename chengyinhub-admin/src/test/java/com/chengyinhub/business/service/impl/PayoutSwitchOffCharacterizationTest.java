package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.CoopSettlementAdjustmentMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.mapper.CoopSettlementOriginalMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.RefundRequestResult;
import com.chengyinhub.common.config.WxPayConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * l-payout2 特征测试（characterization）：在接入电商收付通之前，锁定三处被接入点的现有输出。
 * 本类只用接入前就存在的 API，接入前后各跑一次，三条金标准必须逐字相同：
 * <ol>
 *   <li>报名下单：发给微信的 JSAPI 预下单请求与返回给小程序的调起参数；</li>
 *   <li>原路退款：发给微信的退款请求与受理结果；</li>
 *   <li>T+14 到期结算入余额：入账金额、流水与终态。</li>
 * </ol>
 * 子类 {@code PayoutSwitchOffWiredCharacterizationTest} 在开关全关的前提下装上新接线，复跑同一组金标准。
 */
class PayoutSwitchOffCharacterizationTest {

    static final String GOLDEN_PREPAY = "{amount=6900 CNY, appid=wx_mini, description=静安探店日-首期通票, mchid=1900000109, "
            + "notify=https://api.example.com/prod-api/api/registration/callback, openid=o_member_2, out_trade_no=R-CHAR-0001}";
    static final String GOLDEN_PARAMS = "{appId=wx_mini, nonceStr=nonce, package=prepay_id=wx1, paySign=signed, signType=RSA, timeStamp=1412000000}";
    static final String GOLDEN_REFUND = "ACCEPTED|{amount=200/1000 CNY, out_refund_no=RF1, reason=partial, transaction_id=TX1}";
    static final String GOLDEN_SETTLEMENT = "paid=1|addBalance=300:30.00|detail=5:700:30.00:100.00->130.00|markPaid=700";

    /** 子类在这里装上新接线（开关全关）；本类不改任何东西。 */
    protected void customizeGateway(WechatPayServiceImpl service) { }

    protected void customizeTopicService(CmsTopicServiceImpl service) { }

    protected void afterScenario() { }

    @Test
    void registrationPrepayRequestIsUnchanged() {
        WxPayConfig config = mock(WxPayConfig.class);
        when(config.isWechatPayEnabled()).thenReturn(true);
        when(config.getAppid()).thenReturn("wx_mini");
        when(config.getMerchantId()).thenReturn("1900000109");
        when(config.getNotifyUrl()).thenReturn("https://api.example.com/prod-api/api/registration/callback");
        WechatPayServiceImpl service = WechatVerifiedFactTestSupport.gateway();
        WechatPaymentIntentTestSupport.install(service, Map.of("R-CHAR-0001", new BigDecimal("69.00")));
        WechatVerifiedFactTestSupport.setField(service, "wxPayConfig", config);
        JsapiServiceExtension jsapi = mock(JsapiServiceExtension.class);
        WechatVerifiedFactTestSupport.setField(service, "jsapiService", WechatPaymentIntentTestSupport.absentOrderSdk());
        WechatVerifiedFactTestSupport.setField(service, "jsapiServiceExtension", jsapi);
        WechatVerifiedFactTestSupport.setField(service, "refundService", mock(RefundService.class));
        WechatVerifiedFactTestSupport.setField(service, "notificationParser",
                mock(com.wechat.pay.java.core.notification.NotificationParser.class));
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember member = new UmsMember();
        member.setId(2L);
        member.setWxOpenId("o_member_2");
        when(members.selectUmsMemberById(2L)).thenReturn(member);
        WechatVerifiedFactTestSupport.setField(service, "paymentMemberMapper", members);
        PrepayWithRequestPaymentResponse response = new PrepayWithRequestPaymentResponse();
        response.setAppId("wx_mini");
        response.setTimeStamp("1412000000");
        response.setNonceStr("nonce");
        response.setPackageVal("prepay_id=wx1");
        response.setSignType("RSA");
        response.setPaySign("signed");
        when(jsapi.prepayWithRequestPayment(any())).thenReturn(response);
        customizeGateway(service);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setMemberId(2L);
        reg.setOwnerType(1L);
        reg.setOwnerId(9L);
        reg.setRegistrationNo("R-CHAR-0001");
        reg.setWechatPaymentAmount(new BigDecimal("69.00"));
        reg.setActivityTitle("静安探店日");
        reg.setTicketName("首期通票");
        Map<String, String> params = service.createOrder(reg);

        ArgumentCaptor<PrepayRequest> captured = ArgumentCaptor.forClass(PrepayRequest.class);
        verify(jsapi).prepayWithRequestPayment(captured.capture());
        PrepayRequest r = captured.getValue();
        Map<String, String> trace = new TreeMap<>();
        trace.put("appid", r.getAppid());
        trace.put("mchid", r.getMchid());
        trace.put("out_trade_no", r.getOutTradeNo());
        trace.put("amount", r.getAmount().getTotal() + " " + r.getAmount().getCurrency());
        trace.put("openid", r.getPayer().getOpenid());
        trace.put("description", r.getDescription());
        trace.put("notify", r.getNotifyUrl());
        assertEquals(GOLDEN_PREPAY, trace.toString());
        assertEquals(GOLDEN_PARAMS, new TreeMap<>(params).toString());
        afterScenario();
    }

    @Test
    void originalRefundRequestIsUnchanged() {
        WechatPayServiceImpl gateway = WechatVerifiedFactTestSupport.gateway();
        WechatVerifiedPaymentFactService facts = new WechatVerifiedPaymentFactService();
        WechatVerifiedFactTestSupport.MemoryMapper rows = new WechatVerifiedFactTestSupport.MemoryMapper();
        com.wechat.pay.java.service.payments.jsapi.JsapiService payments = mock(com.wechat.pay.java.service.payments.jsapi.JsapiService.class);
        RefundService refunds = mock(RefundService.class);
        WechatPaymentIntentStore intents = mock(WechatPaymentIntentStore.class);
        WechatVerifiedFactTestSupport.setField(facts, "mapper", rows);
        WechatVerifiedPaymentFact.Reference paid = facts.recordVerified(new WechatVerifiedPaymentFact(
                WechatVerifiedPaymentFact.Kind.PAYMENT, "merchant", "wx-original", "REG1", "W1", "W1", "TX1", "TX1",
                "APP", "CNY", 1000, 1000, Instant.parse(WechatVerifiedFactTestSupport.SUCCESS_TIME), "a".repeat(64), "SUCCESS"));
        WechatVerifiedFactTestSupport.setField(gateway, "verifiedFacts", facts);
        WechatVerifiedFactTestSupport.setField(gateway, "intents", intents);
        WechatVerifiedFactTestSupport.setField(gateway, "wxPayConfig", new WxPayConfig());
        WechatVerifiedFactTestSupport.setField(gateway, "refundService", refunds);
        WechatVerifiedFactTestSupport.bind(gateway, payments, refunds, "merchant");
        when(intents.businessPaymentOrderNo("W1", "merchant")).thenReturn("REG1");
        when(intents.frozenRequestHash("merchant", "W1")).thenReturn("a".repeat(64));
        when(intents.frozenFacts("merchant", "W1")).thenReturn(List.of(Map.of(
                "appid", "wx-original", "trade_type", "APP", "currency", "CNY", "total_fen", 1000L)));
        Refund accepted = new Refund();
        accepted.setStatus(Status.PROCESSING);
        accepted.setTransactionId("TX1");
        accepted.setOutTradeNo("W1");
        accepted.setOutRefundNo("RF1");
        com.wechat.pay.java.service.refund.model.Amount amount = new com.wechat.pay.java.service.refund.model.Amount();
        amount.setTotal(1000L);
        amount.setRefund(200L);
        amount.setCurrency("CNY");
        accepted.setAmount(amount);
        when(refunds.create(any())).thenReturn(accepted);
        customizeGateway(gateway);

        RefundRequestResult result = gateway.submitRefund(paid, "REG1", "RF1", new BigDecimal("2.00"),
                new BigDecimal("10.00"), "partial");

        ArgumentCaptor<CreateRequest> captured = ArgumentCaptor.forClass(CreateRequest.class);
        verify(refunds).create(captured.capture());
        CreateRequest r = captured.getValue();
        Map<String, String> trace = new TreeMap<>();
        trace.put("transaction_id", r.getTransactionId());
        trace.put("out_refund_no", r.getOutRefundNo());
        trace.put("amount", r.getAmount().getRefund() + "/" + r.getAmount().getTotal() + " " + r.getAmount().getCurrency());
        trace.put("reason", r.getReason());
        assertEquals(GOLDEN_REFUND, result + "|" + trace);
        afterScenario();
    }

    @Test
    void dueCoopSettlementBalanceCreditIsUnchanged() {
        CmsTopicServiceImpl target = new CmsTopicServiceImpl();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(target, "transactionManager", tm);
        CmsTopic topic = new CmsTopic();
        topic.setId(200L);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(200L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        CoopSettlement head = new CoopSettlement();
        head.setId(700L);
        head.setTopicId(200L);
        head.setMerchantId(300L);
        head.setAmount(new BigDecimal("30.00"));
        head.setStatus(0);
        head.setVerifiedHeads(1);
        CoopSettlementMapper settlements = mock(CoopSettlementMapper.class);
        when(settlements.selectDuePayouts(any())).thenReturn(List.of(head));
        when(settlements.selectDuePayoutForUpdate(eq(700L), any())).thenReturn(head);
        when(settlements.markPaidIfPending(eq(700L), any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "coopSettlementMapper", settlements);
        CoopSettlementOriginalMapper originals = mock(CoopSettlementOriginalMapper.class);
        when(originals.selectTopicHeadsForUpdate(200L)).thenReturn(List.of(head));
        when(originals.selectAdjustmentsForSettlementForUpdate(700L)).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "coopSettlementOriginalMapper", originals);
        CoopSettlementAdjustmentMapper adjustments = mock(CoopSettlementAdjustmentMapper.class);
        when(adjustments.selectExecutedBySettlementForUpdate(700L)).thenReturn(List.of());
        ReflectionTestUtils.setField(target, "coopSettlementAdjustmentMapper", adjustments);
        ReflectionTestUtils.setField(target, "coopComplaintMapper", mock(CoopComplaintMapper.class));
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        UmsMember before = new UmsMember();
        before.setId(300L);
        before.setBalance(new BigDecimal("100.00"));
        UmsMember after = new UmsMember();
        after.setId(300L);
        after.setBalance(new BigDecimal("130.00"));
        when(members.lockMemberById(300L)).thenReturn(300L);
        when(members.selectUmsMemberById(300L)).thenReturn(before, after);
        when(members.addBalance(eq(300L), any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        IUmsMemberBalanceDetailService details = mock(IUmsMemberBalanceDetailService.class);
        when(details.insertUmsMemberBalanceDetail(any())).thenReturn(1);
        ReflectionTestUtils.setField(target, "umsMemberBalanceDetailService", details);
        ReflectionTestUtils.setField(target, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));
        customizeTopicService(target);

        int paid = target.payoutDueCoopSettlements();

        ArgumentCaptor<BigDecimal> credited = ArgumentCaptor.forClass(BigDecimal.class);
        verify(members).addBalance(eq(300L), credited.capture());
        ArgumentCaptor<UmsMemberBalanceDetail> detail = ArgumentCaptor.forClass(UmsMemberBalanceDetail.class);
        verify(details).insertUmsMemberBalanceDetail(detail.capture());
        verify(settlements).markPaidIfPending(eq(700L), any(Date.class));
        UmsMemberBalanceDetail d = detail.getValue();
        String trace = "paid=" + paid + "|addBalance=300:" + credited.getValue()
                + "|detail=" + d.getEventType() + ":" + d.getEventId() + ":" + d.getChangeBalance() + ":"
                + d.getCurrentBalance() + "->" + d.getAfterBalance() + "|markPaid=700";
        assertEquals(GOLDEN_SETTLEMENT, trace);
        verify(members).lockMemberById(anyLong());
        afterScenario();
    }
}
