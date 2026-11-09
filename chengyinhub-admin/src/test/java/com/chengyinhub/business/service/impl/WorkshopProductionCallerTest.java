package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiWorkshopController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkshopProductionCallerTest {
    private static final String NO="WKO_"+"1".repeat(32), VERSION="WKV_"+"2".repeat(32);
    private final WorkshopMapper mapper=mock(WorkshopMapper.class);
    private final ITemplatePublishService drafts=mock(ITemplatePublishService.class);
    private final IWechatPayService payments=mock(IWechatPayService.class);
    private final WechatVerifiedPaymentFactService verified=mock(WechatVerifiedPaymentFactService.class);
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,Object> order=new HashMap<>(), version=new HashMap<>();
    private final AtomicReference<Map<String,Object>> receipt=new AtomicReference<>();
    private WorkshopService service;
    private ApiWorkshopController api;
    private WechatPaymentCallbackRouter router;
    private CmsMemberTemplate target;

    @BeforeEach void setup() throws Exception {
        service=new WorkshopService(mapper,drafts,payments,verified,new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,TransactionDefinition definition){}
            @Override protected void doCommit(DefaultTransactionStatus status){}
            @Override protected void doRollback(DefaultTransactionStatus status){}
        });
        api=new ApiWorkshopController(service) {
            @Override public Long getAppUserId(){return 7L;}
            @Override public String getAppUserName(){return "buyer";}
        };
        router=new WechatPaymentCallbackRouter(null,null,null,null,null,null,service);
        order.put("id",91L); order.put("order_no",NO); order.put("buyer_id",7L); order.put("version_id",VERSION);
        order.put("amount_fen",123L); order.put("provider","WECHAT_APP"); order.put("state","PENDING"); order.put("license_status","UNPAID");
        when(mapper.lockOrder(NO)).thenReturn(order);
        CmsTemplatePublishRequest content=new CmsTemplatePublishRequest(); content.setTitle("frozen");
        content.setOriginalTemplateId(8L); content.setQuestionAnswer("purchased answer"); content.setValidationMethod(1);
        String contentJson=json.writeValueAsString(content), termsJson=json.writeValueAsString(new WorkshopService.Terms("terms1","author terms",false,List.of("上海"),2));
        version.put("version_id",VERSION); version.put("module_id",8L); version.put("seller_id",99L);
        version.put("content_json",contentJson); version.put("content_hash",WechatVerifiedPaymentFact.sha256(contentJson));
        version.put("terms_json",termsJson); version.put("terms_hash",WechatVerifiedPaymentFact.sha256(termsJson)); version.put("price_fen",123L);
        approveFixture(version);
        when(mapper.version(VERSION)).thenReturn(version);
        when(mapper.orderRequest(anyLong(),anyString())).thenReturn(null);
        when(mapper.activate(anyMap())).thenAnswer(call -> {
            Map<String,Object> row=call.getArgument(0); order.put("state","PAID"); order.put("license_status","ACTIVE");
            order.put("paid_at",row.get("paidAt")); order.put("payment_fact_id",row.get("factId")); order.put("payment_fact_hash",row.get("factHash")); return 1;
        });
        when(mapper.suspendRefund(anyMap())).thenAnswer(call -> {
            order.put("state","REFUND_PENDING"); order.put("license_status","SUSPENDED"); order.put("refund_no","R"+NO); return 1;
        });
        when(mapper.refunded(anyMap())).thenAnswer(call -> {
            Map<String,Object> row=call.getArgument(0); order.put("state","REFUNDED"); order.put("license_status","REVOKED");
            order.put("refund_fact_id",row.get("factId")); order.put("refund_fact_hash",row.get("factHash")); return 1;
        });
        when(mapper.revoke(anyMap())).thenAnswer(call -> {order.put("license_status","REVOKED");return 1;});
        when(mapper.reserveInstall(anyMap())).thenAnswer(call -> {
            if(receipt.get()==null){Map<String,Object> r=call.getArgument(0); Map<String,Object> stored=new HashMap<>();
                stored.put("order_no",r.get("orderNo")); stored.put("command_hash",r.get("commandHash")); stored.put("command_json",r.get("commandJson")); receipt.set(stored);}
            return 1;
        });
        when(mapper.install(eq(7L),anyString())).thenAnswer(call -> receipt.get());
        when(mapper.finishInstall(anyMap())).thenAnswer(call -> {
            Map<String,Object> r=call.getArgument(0); receipt.get().put("outcome_json",r.get("outcomeJson")); receipt.get().put("draft_id",r.get("draftId")); return 1;
        });
        target=privateDraft(20,7); when(mapper.lockDraft(20L)).thenReturn(target);
        when(mapper.targets(7L,Long.MAX_VALUE)).thenReturn(List.of(target));
        when(drafts.installWorkshopSnapshot(any(),eq(7L),eq("buyer"))).thenReturn(21L);
        when(mapper.lockDraft(21L)).thenReturn(privateDraft(21,7));
    }

    private void approveFixture(Map<String,Object> row) throws Exception {
        row.put("source_kind","PRIVATE_TEMPLATE");row.put("review_state","APPROVED");row.put("id",4L);
        var snapshot=new java.util.TreeMap<String,Object>();
        for(String field:List.of("version_id","seller_id","module_id","content_hash","terms_hash","price_fen"))
            snapshot.put(field,row.get(field));
        snapshot.put("content",json.readTree(row.get("content_json").toString()));
        snapshot.put("terms",json.readTree(row.get("terms_json").toString()));
        snapshot.put("appleProducts",List.of());
        String raw=json.writeValueAsString(snapshot);
        row.put("review_snapshot",raw);row.put("review_hash",WechatVerifiedPaymentFact.sha256(raw));
    }

    @Test void providerUnavailableAndIapCannotCreateOrderOrPayment() {
        assertEquals(false,((Map<?,?>)service.capabilities().get("appleIap")).get("available"));
        assertEquals("CHANNEL_NOT_CONFIGURED",assertThrows(ServiceException.class,()->api.purchase(purchase("WECHAT_APP"))).getMessage());
        assertEquals("CHANNEL_NOT_CONFIGURED",assertThrows(ServiceException.class,()->api.purchase(purchase("APPLE_IAP"))).getMessage());
        assertThrows(ServiceException.class,()->api.pay(NO)); verify(mapper,never()).reserveOrder(anyMap()); verifyNoInteractions(payments);
    }

    @Test void enabledOrderUsesFrozenPriceAndOriginalGenericAppProvider() {
        ReflectionTestUtils.setField(service,"appEnabled",true); when(payments.isAppReady()).thenReturn(true);
        when(mapper.reserveOrder(anyMap())).thenAnswer(call -> {Map<String,Object> r=call.getArgument(0); order.put("request_hash",r.get("requestHash"));return 1;});
        when(mapper.orderRequest(7L,"buy1")).thenReturn(null,order);
        api.purchase(purchase("WECHAT_APP")); api.pay(NO);
        verify(payments).createGenericAppOrder(NO,new java.math.BigDecimal("1.23"),"城瘾玩法模板");
        assertEquals("UNPAID",order.get("license_status")); verify(mapper,never()).activate(anyMap());
    }

    @Test void realRouterActivatesOnlyOriginalVerifiedFactAndReplayNeverRestoresRevokedLicense() {
        var fact=payment(123); var ref=stub(fact,3); Map<String,String> callback=paymentObservation(ref);
        assertEquals(new WechatPaymentCallbackRouter.Outcome("workshop","SUCCESS"),router.payment(callback));
        assertEquals("ACTIVE",order.get("license_status"));
        api.revoke(NO); assertEquals("REVOKED",order.get("license_status"));
        assertEquals("SUCCESS",router.payment(callback).result()); assertEquals("REVOKED",order.get("license_status"));
        verify(mapper,times(1)).activate(anyMap()); verify(verified,times(2)).requireConsumablePayment(ref);
    }

    @Test void originalWechatFacadeStillVerifiesBeforeProductionWorkshopRoute() {
        var ref=stub(payment(123),3);
        var gateway=mock(WechatPaymentSdkGateway.class);
        var transaction=new com.wechat.pay.java.service.payments.model.Transaction(); transaction.setOutTradeNo(NO);
        when(gateway.parsePaymentNotification(anyString(),anyMap())).thenReturn(transaction);
        when(gateway.verifiedPaymentFacts(transaction)).thenReturn(paymentObservation(ref));
        var facade=new WechatPayServiceImpl();
        ReflectionTestUtils.setField(facade,"gateway",gateway);ReflectionTestUtils.setField(facade,"callbacks",router);
        assertTrue(facade.handlePayCallback("provider notification",Map.of()).contains("SUCCESS"));
        assertEquals("ACTIVE",order.get("license_status"));
        org.mockito.InOrder order=org.mockito.Mockito.inOrder(gateway,verified,mapper);
        order.verify(gateway).parsePaymentNotification(anyString(),anyMap());
        order.verify(gateway).verifiedPaymentFacts(transaction);
        order.verify(mapper).lockOrder(NO);
        order.verify(verified).requireConsumablePayment(ref);
    }

    @Test void clientSuccessMissingVerifiedReferenceCannotActivate() {
        assertThrows(ServiceException.class,()->router.payment(Map.of("out_trade_no",NO,"trade_state","SUCCESS","paid","true")));
        verify(mapper,never()).activate(anyMap()); verifyNoInteractions(verified);
    }

    @Test void wrongAmountAndForeignBusinessFactAreRejectedBeforeActivation() {
        var wrongAmountRef=stub(payment(124),3); assertThrows(ServiceException.class,()->router.payment(paymentObservation(wrongAmountRef)));
        var other=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app","WKO_"+"9".repeat(32),"wx1","wx1","tx1","tx1","APP","CNY",123,123,Instant.EPOCH,null,"SUCCESS");
        var finalRef=stub(other,4); assertThrows(ServiceException.class,()->router.payment(paymentObservation(finalRef)));
        verify(mapper,never()).activate(anyMap());
    }

    @Test void unavailableOrOverlappingWorkshopRouteCannotFallThroughToRegistration() {
        var registration=mock(ICmsRegistrationService.class);
        var reserved=new WechatPaymentCallbackRouter(null,null,null,registration,null,null);
        assertEquals("FAIL",reserved.payment(Map.of("out_trade_no",NO,"trade_state","SUCCESS")).result());
        when(registration.getByRegistrationNo(NO)).thenReturn(new com.chengyinhub.business.domain.CmsRegistration());
        assertThrows(WechatPaymentCallbackRouter.RoutingException.class,()->reserved.payment(Map.of("out_trade_no",NO)));
        verify(registration,never()).handleWechatPayCallback(anyMap());
    }

    @Test void controllerInstallsFrozenPrivateCopyAndLostAckReplaysWithoutSecondDraft() throws Exception {
        activate(); String raw=command();
        var first=api.install(raw); var again=api.install(raw);
        assertEquals(first.get("data"),again.get("data"));
        Map<?,?> outcome=(Map<?,?>)first.get("data"); assertEquals(false,outcome.get("published")); assertEquals(false,outcome.get("executable"));
        assertEquals(21L,((Number)outcome.get("ownedDraftId")).longValue());
        verify(drafts).installWorkshopSnapshot(argThat(c -> "purchased answer".equals(c.getQuestionAnswer())),eq(7L),eq("buyer"));
        assertEquals(first.get("data"),api.installStatus("install1").get("data"));
        api.revoke(NO); assertEquals(first.get("data"),api.install(raw).get("data"));
        assertThrows(ServiceException.class,()->api.text(NO,"install1"));
    }

    @Test void sameRequestCannotChangeCommandBytesEvenWhitespace() throws Exception {
        activate(); String raw=command(); api.install(raw);
        assertThrows(ServiceException.class,()->api.install(raw+" ")); verify(drafts,times(1)).installWorkshopSnapshot(any(),any(),any());
    }

    @Test void inactiveLicenseForeignOwnerAndTargetRevisionDoNotReachDraftWriter() throws Exception {
        assertThrows(ServiceException.class,()->api.install(command()));
        activate(); String raw=command(); target.setUpdateTime(new Date(2000));
        assertThrows(ServiceException.class,()->api.install(raw)); target.setUpdateTime(new Date(1000)); target.setMemberId(8L);
        assertThrows(ServiceException.class,()->api.install(raw)); order.put("buyer_id",8L);
        assertThrows(ServiceException.class,()->api.license(NO)); verifyNoInteractions(drafts);
    }

    @Test void frozenHashesRegionCommercialUseAndInstallLimitAreEnforced() throws Exception {
        activate(); String raw=command();
        assertThrows(ServiceException.class,()->api.install(raw.replace("上海","北京")));
        receipt.set(null); assertThrows(ServiceException.class,()->api.install(raw.replace("\"commercialUse\":false","\"commercialUse\":true")));
        receipt.set(null); assertThrows(ServiceException.class,()->api.install(raw.replace((String)version.get("content_hash"),"f".repeat(64))));
        receipt.set(null); when(mapper.installedCount(NO)).thenReturn(2); assertThrows(ServiceException.class,()->api.install(raw));
        verifyNoInteractions(drafts);
    }

    @Test void refundSubmissionSuspendsImmediatelyAndOnlyOriginalRefundFactRevokes() throws Exception {
        activate(); String raw=command(); when(payments.submitRefund(any(Reference.class),anyString(),anyString(),any(),any(),anyString())).thenReturn(RefundRequestResult.UNKNOWN);
        api.refund(NO); assertEquals("SUSPENDED",order.get("license_status")); assertThrows(ServiceException.class,()->api.install(raw));
        var original=payment(123); var refund=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"mch","app",NO,"wx1","R"+NO,"refund1","tx1","APP","CNY",123,123,Instant.ofEpochSecond(5),null,"REFUND");
        var ref=stub(refund,4); when(verified.requireVerified(paymentReference())).thenReturn(new WechatVerifiedPaymentFactService.Stored(paymentReference(),original));
        var observation=new HashMap<String,String>(); observation.put("outTradeNo",NO); observation.put("outRefundNo","R"+NO); observation.put("status","SUCCESS"); addReference(observation,ref);
        assertTrue(router.refund(observation)); assertEquals("REVOKED",order.get("license_status")); assertEquals("REFUNDED",order.get("state"));
        assertTrue(router.refund(observation)); verify(mapper,times(1)).refunded(anyMap()); verifyNoInteractions(drafts);
    }

    @Test void refundWrongOriginalTransactionCannotRevoke() {
        activate(); order.put("state","REFUND_PENDING"); order.put("refund_no","R"+NO);
        var wrong=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"mch","app",NO,"wx1","R"+NO,"refund1","wrongTx","APP","CNY",123,123,Instant.EPOCH,null,"REFUND");
        var ref=stub(wrong,5); var observed=new HashMap<String,String>(); observed.put("outTradeNo",NO); observed.put("outRefundNo","R"+NO); observed.put("status","SUCCESS");addReference(observed,ref);
        assertThrows(ServiceException.class,()->router.refund(observed)); verify(mapper,never()).refunded(anyMap());
    }

    @Test void authorCannotPriceForeignHiddenOrAdoptedSourceAndOfferDoesNotLeakAnswers() {
        CmsMemberTemplate canonical=privateDraft(80,99);canonical.setTitle("author title");canonical.setQuestionAnswer("private secret");
        when(mapper.lockDraft(80)).thenReturn(canonical);when(mapper.lockLicenseForDraft(80)).thenReturn(null);
        assertThrows(ServiceException.class,()->service.authorSource(7,80));
        canonical.setOriginalTemplateId(3L);assertThrows(ServiceException.class,()->service.authorSource(99,80));
        canonical.setOriginalTemplateId(0L);canonical.setPublishStatus(1L);assertThrows(ServiceException.class,()->service.authorSource(99,80));
        canonical.setPublishStatus(0L);when(mapper.sourceExposure(80)).thenReturn(1);
        assertThrows(ServiceException.class,()->service.authorSource(99,80));
        assertFalse(service.offer(VERSION).containsKey("content_json"));assertFalse(service.offer(VERSION).toString().contains("purchased answer"));
    }

    @Test void legacyContractAndAnonymousIdentityFailClosed() {
        assertEquals(409,api.legacy().get("code"));
        var anonymous=new ApiWorkshopController(service){@Override public Long getAppUserId(){return null;}};
        assertThrows(ServiceException.class,()->anonymous.license(NO)); assertThrows(ServiceException.class,()->anonymous.legacy());
    }

    @Test void businessOwnerLocksWorkshopMoneyInCentsAndDoesNotGuessRegistration() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var owner=new JdbcWechatPaymentOrderOwner();ReflectionTestUtils.setField(owner,"paymentIntentJdbc",jdbc);
        when(jdbc.queryForList(contains("FROM workshop_order"),eq(NO))).thenReturn(List.of(Map.of("id",91L,"amount",123L,"payable",1)));
        var locked=owner.lock(NO);assertEquals("WORKSHOP",locked.type());assertEquals(91L,locked.id().longValue());
        assertEquals(new java.math.BigDecimal("123"),locked.amount());
        verify(jdbc,never()).queryForList(contains("cms_registration"),any(Object[].class));
    }

    private WorkshopService.Purchase purchase(String provider){return new WorkshopService.Purchase("buy1",VERSION,(String)version.get("content_hash"),(String)version.get("terms_hash"),provider);}
    private void activate(){router.payment(paymentObservation(stub(payment(123),3)));}
    private WechatVerifiedPaymentFact payment(long amount){return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app",NO,"wx1","wx1","tx1","tx1","APP","CNY",amount,amount,Instant.ofEpochSecond(1),null,"SUCCESS");}
    private Reference stub(WechatVerifiedPaymentFact fact,long id){
        var ref=new Reference(id,fact.factHash());var stored=new WechatVerifiedPaymentFactService.Stored(ref,fact);
        when(verified.requireVerified(ref)).thenReturn(stored);when(verified.requireConsumablePayment(ref)).thenReturn(stored);return ref;
    }
    private Reference paymentReference(){return new Reference((Long)order.get("payment_fact_id"),(String)order.get("payment_fact_hash"));}
    private Map<String,String> paymentObservation(Reference ref){var out=new HashMap<String,String>();out.put("out_trade_no",NO);out.put("trade_state","SUCCESS");addReference(out,ref);return out;}
    private void addReference(Map<String,String> map,Reference ref){map.put("verified_fact_id",Long.toString(ref.id()));map.put("verified_fact_hash",ref.hash());}
    private String command() throws Exception {
        Map<?,?> t=(Map<?,?>)((List<?>)((Map<?,?>)api.targets(NO,null).get("data")).get("items")).get(0);
        return json.writeValueAsString(new WorkshopService.Install("workshop-private-draft-install-command-v2","install1",NO,8,VERSION,
                (String)version.get("content_hash"),(String)version.get("terms_hash"),20,1000,"MEMBER_TEMPLATE",(String)t.get("targetPayloadHash"),"上海",false));
    }
    private static CmsMemberTemplate privateDraft(long id,long owner){var row=new CmsMemberTemplate();row.setId(id);row.setMemberId(owner);row.setTitle("draft");row.setDelFlag(0);row.setDraftStatus(0);row.setStatus(0L);row.setPublishStatus(0L);row.setUpdateTime(new Date(1000));return row;}
}
