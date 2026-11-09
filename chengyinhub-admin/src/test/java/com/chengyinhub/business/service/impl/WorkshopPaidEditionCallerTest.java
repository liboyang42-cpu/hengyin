package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.appleiap.*;
import com.chengyinhub.business.appleiap.mapper.AppleIapMapper;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apple.itunes.storekit.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual production service/audit/order callers; storage and signed SDK results are explicit mocks. */
class WorkshopPaidEditionCallerTest {
    final WorkshopMapper mapper=mock(WorkshopMapper.class);
    final ITemplatePublishService drafts=mock(ITemplatePublishService.class);
    final IWechatPayService pay=mock(IWechatPayService.class);
    final ObjectMapper json=new ObjectMapper();
    final Map<String,Object> version=new HashMap<>(),order=new HashMap<>();
    final CmsMemberTemplate source=new CmsMemberTemplate();
    final AuditTask task=new AuditTask();
    final AtomicReference<Map<String,Object>> frozen=new AtomicReference<>(),created=new AtomicReference<>();
    final AppleIapProperties config=new AppleIapProperties();
    final AppleSignedData signed=mock(AppleSignedData.class);
    final WorkshopVersionReview review=new WorkshopVersionReview(mapper);
    final AbstractPlatformTransactionManager transactions=new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object t,TransactionDefinition d){}
        @Override protected void doCommit(DefaultTransactionStatus s){}
        @Override protected void doRollback(DefaultTransactionStatus s){}
    };
    WorkshopService service;
    Map<String,Object> offer;
    List<Map<String,Object>> products;

    @BeforeEach void setup() {
        service=new WorkshopService(mapper,drafts,pay,mock(WechatVerifiedPaymentFactService.class),transactions);
        ReflectionTestUtils.setField(service,"versionReview",review);
        ReflectionTestUtils.setField(service,"appleSignedData",signed);
        ReflectionTestUtils.setField(service,"appleProperties",config);
        ReflectionTestUtils.setField(service,"appEnabled",true);when(pay.isAppReady()).thenReturn(true);
        when(signed.isReady()).thenReturn(true);
        config.setEnabled(true);config.setBundleId("org.chengyin.app");config.setEnvironment("Sandbox");
        config.setSkuPrices(Map.of("paid.edition",Map.of("USD",990L)));
        products=new ArrayList<>(List.of(new HashMap<>(Map.of("bundle_id",config.getBundleId(),"environment","Sandbox",
                "product_id","paid.edition","price_milliunits",990L,"currency","USD"))));
        when(mapper.appleProducts(anyString())).thenAnswer(c->products);
        source.setId(80L);source.setMemberId(99L);source.setTitle("private paid edition");source.setQuestionAnswer("paid secret");
        source.setStatus(0L);source.setDraftStatus(0);source.setPublishStatus(0L);source.setDelFlag(0);source.setCheckStatus(0);
        when(mapper.lockDraft(80)).thenReturn(source);when(mapper.lockLicenseForDraft(anyLong())).thenReturn(null);
        when(mapper.versionRequest(eq(99L),anyString())).thenAnswer(c->frozen.get());
        when(mapper.insertVersion(anyMap())).thenAnswer(c->{
            Map<String,Object> row=c.getArgument(0);
            for(var pair:Map.of("version_id","versionId","seller_id","owner","module_id","moduleId",
                    "content_hash","contentHash","content_json","contentJson","terms_hash","termsHash",
                    "terms_json","termsJson","price_fen","priceFen","request_hash","requestHash").entrySet())
                version.put(pair.getKey(),row.get(pair.getValue()));
            version.put("id",1L);version.put("source_kind","PRIVATE_TEMPLATE");version.put("review_state","DRAFT");
            frozen.set(version);return 1;
        });
        when(mapper.version(anyString())).thenAnswer(c->version);
        when(mapper.lockVersionById(1)).thenAnswer(c->version);
        var submissions=mock(IAuditTaskService.class);
        when(submissions.submit(eq(WorkshopVersionReview.BIZ_TYPE),eq(1L),eq(99L),eq("auto_gate"),anyString())).thenAnswer(c->{
            task.setId(500L);task.setBizId(1L);task.setBizType(WorkshopVersionReview.BIZ_TYPE);
            task.setSubmitterId(99L);task.setSourceType("auto_gate");task.setStatus(0);task.setVersion(0);
            task.setSnapshotJson(c.getArgument(4));return 500L;
        });
        ReflectionTestUtils.setField(review,"audits",submissions);
        ReflectionTestUtils.setField(review,"drafts",drafts);
        when(mapper.submitVersion(anyMap())).thenAnswer(c->{
            Map<String,Object> row=c.getArgument(0);version.put("review_state","PENDING");
            version.put("review_snapshot",row.get("reviewSnapshot"));version.put("review_hash",row.get("reviewHash"));
            version.put("audit_task_id",row.get("auditTaskId"));return 1;
        });
        when(mapper.reviewVersion(anyMap())).thenAnswer(c->{
            if(!"PENDING".equals(version.get("review_state")))return 0;
            version.put("review_state",((Map<?,?>)c.getArgument(0)).get("reviewState"));return 1;
        });
        when(mapper.orderRequest(eq(7L),anyString())).thenAnswer(c->created.get());
        when(mapper.reserveOrder(anyMap())).thenAnswer(c->{
            Map<String,Object> row=c.getArgument(0);
            for(var pair:Map.ofEntries(Map.entry("order_no","orderNo"),Map.entry("buyer_id","owner"),
                    Map.entry("version_id","versionId"),Map.entry("request_hash","requestHash"),Map.entry("provider","provider"),
                    Map.entry("version_review_hash","reviewHash"),Map.entry("amount_fen","amountFen"),Map.entry("currency","currency"),
                    Map.entry("amount_milliunits","amountMilliunits"),Map.entry("app_account_token","accountToken"),
                    Map.entry("apple_bundle_id","bundleId"),Map.entry("apple_environment","environment"),Map.entry("apple_product_id","productId")).entrySet())
                order.put(pair.getKey(),row.get(pair.getValue()));
            order.put("id",91L);order.put("state","PENDING");order.put("license_status","UNPAID");created.set(order);return 1;
        });
        when(mapper.lockOrder(anyString())).thenAnswer(c->order.isEmpty()?null:order);
        when(mapper.lockAppleOrder(anyString(),anyString())).thenAnswer(c->order.isEmpty()?null:order);
        when(mapper.lockBoundAppleOrder(anyString(),anyString(),anyString())).thenReturn(null);
        when(mapper.activateApple(anyMap())).thenAnswer(c->{Map<?,?> r=c.getArgument(0);
            order.put("state","PAID");order.put("license_status","ACTIVE");bindFact(r);return 1;});
        when(mapper.revokeApple(anyMap())).thenAnswer(c->{Map<?,?> r=c.getArgument(0);
            order.put("state","REFUNDED");order.put("license_status","REVOKED");bindFact(r);return 1;});
        when(mapper.withdrawVersion(anyString(),eq(99L))).thenAnswer(c->{version.put("review_state","WITHDRAWN");return 1;});
    }
    private void bindFact(Map<?,?> row) {
        order.put("apple_transaction_id",row.get("transactionId"));order.put("apple_original_transaction_id",row.get("originalId"));
        order.put("apple_fact_hash",row.get("factHash"));order.put("paid_at",row.get("paidAt"));
    }
    private void freeze() {
        offer=service.freeze(99,new WorkshopService.Freeze("freeze",80,(String)service.authorSource(99,80).get("contentHash"),123,
                new WorkshopService.Terms("t1","private edition terms",false,List.of("310100"),2)));
    }
    private void submit() {service.submitVersion(99,new WorkshopService.ReviewSubmission(id(),(String)offer.get("contentHash"),(String)offer.get("termsHash"),123));}
    private String id(){return (String)offer.get("purchasedVersionId");}
    private WorkshopService.Purchase purchase(String provider) {
        return new WorkshopService.Purchase("buy",id(),(String)offer.get("contentHash"),(String)offer.get("termsHash"),provider,(String)version.get("review_hash"));
    }
    @SuppressWarnings("unchecked")
    private AuditTaskServiceImpl audit() {
        var audits=new AuditTaskServiceImpl();var tasks=mock(AuditTaskMapper.class);var records=mock(AuditRecordMapper.class);
        var events=mock(BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(audits,"auditTaskMapper",tasks);ReflectionTestUtils.setField(audits,"auditRecordMapper",records);
        ReflectionTestUtils.setField(audits,"businessAuditEventMapper",events);ReflectionTestUtils.setField(audits,"workshopVersionReview",review);
        ObjectProvider<IImService> messages=mock(ObjectProvider.class);when(messages.getObject()).thenReturn(mock(IImService.class));
        ReflectionTestUtils.setField(audits,"imServiceProvider",messages);
        when(tasks.selectAuditTaskById(500L)).thenReturn(task);when(tasks.selectAuditTaskByIdForUpdate(500L)).thenReturn(task);
        when(tasks.updateAuditTaskReviewCas(eq(500L),eq(0),eq(1),eq(6L),eq(0),any())).thenReturn(1);
        when(records.insertAuditRecord(any())).thenReturn(1);when(events.insertBusinessAuditEvent(any())).thenReturn(1);
        return audits;
    }
    private AuditReviewDTO decision() {var d=new AuditReviewDTO();d.setStatus(1);d.setExpectedVersion(0);d.setRequestId("review");return d;}
    private void approved() {freeze();submit();assertEquals(1,audit().review(500L,6L,decision()));}

    @Test void privateFreezeUsesOriginalAuditDecisionAndNeverPublishesFreeProjection() {
        freeze();assertEquals("DRAFT",offer.get("reviewState"));assertThrows(ServiceException.class,()->service.offer(id()));
        assertThrows(ServiceException.class,()->service.purchase(7,purchase("WECHAT_APP")));
        submit();assertEquals("PENDING",version.get("review_state"));assertThrows(ServiceException.class,()->service.purchase(7,purchase("WECHAT_APP")));
        assertEquals(1,audit().review(500L,6L,decision()));
        assertEquals("APPROVED",service.offer(id()).get("reviewState"));
        assertFalse(service.offer(id()).toString().contains("paid secret"));
        verify(drafts,never()).installWorkshopSnapshot(any(),anyLong(),anyString());verify(mapper,never()).lockSource(anyLong());
        assertEquals("PENDING",service.purchase(7,purchase("WECHAT_APP")).get("state"));
        assertEquals("UNPAID",order.get("license_status"));
    }
    @Test void originalAuditRequiresExpectedVersionAndExactFrozenContentTermsPrice() {
        freeze();submit();var missing=decision();missing.setExpectedVersion(null);
        assertThrows(ServiceException.class,()->audit().review(500L,6L,missing));
        version.put("price_fen",124L);
        assertThrows(ServiceException.class,()->audit().review(500L,6L,decision()));
        assertEquals("PENDING",version.get("review_state"));verify(mapper,never()).reviewVersion(anyMap());
    }
    @Test void authorEditCannotReplaceReviewedSnapshotAndPricingTamperCannotSell() {
        approved();String frozenContent=version.get("content_json").toString();
        source.setQuestionAnswer("later author answer");assertEquals(frozenContent,version.get("content_json"));
        service.purchase(7,purchase("WECHAT_APP"));assertEquals("UNPAID",order.get("license_status"));
        created.set(null);order.clear();version.put("terms_hash","f".repeat(64));
        assertThrows(ServiceException.class,()->service.purchase(7,purchase("WECHAT_APP")));
    }
    @Test void oldFreePublicAndAdoptedSourcesCannotBecomeIndependentPaidSource() {
        when(mapper.sourceExposure(80)).thenReturn(1);assertThrows(ServiceException.class,this::freeze);
        when(mapper.sourceExposure(80)).thenReturn(0);source.setOriginalTemplateId(8L);assertThrows(ServiceException.class,this::freeze);
        source.setOriginalTemplateId(0L);source.setMemberId(8L);assertThrows(ServiceException.class,this::freeze);
        verify(mapper,never()).insertVersion(anyMap());
    }

    @Test void oldAuthorJsonWithoutPrivateSourceContractAndInvalidCityTermsFailBeforeFreezing() {
        String hash=service.authorSource(99,80).get("contentHash").toString();
        assertThrows(ServiceException.class,()->service.freeze(99,new WorkshopService.Freeze("old",80,hash,123,
                new WorkshopService.Terms("t1","terms",false,List.of("310100"),2),null)));
        assertThrows(ServiceException.class,()->service.freeze(99,new WorkshopService.Freeze("old",80,hash,123,
                new WorkshopService.Terms("t1","terms",false,List.of("上海"),2))));
        verify(mapper,never()).insertVersion(anyMap());
    }
    @Test void unreviewedSkuChangesAndMissingServerPriceNeverProduceAppleOrder() {
        approved();products.get(0).put("currency","CNY");
        assertThrows(ServiceException.class,()->service.purchase(7,purchase("APPLE_IAP")));
        products.get(0).put("currency","USD");config.setSkuPrices(Map.of());
        assertThrows(ServiceException.class,()->service.purchase(7,purchase("APPLE_IAP")));
        verify(mapper,never()).reserveOrder(anyMap());
    }
    @Test void appleOrderUsesExplicitUsdMilliunitsAndRequiresApprovedQuoteHash() {
        approved();var valid=purchase("APPLE_IAP");
        assertThrows(ServiceException.class,()->service.purchase(7,new WorkshopService.Purchase("buy",id(),valid.contentHash(),valid.termsHash(),"APPLE_IAP","a".repeat(64))));
        var result=service.purchase(7,valid);assertEquals("USD",result.get("currency"));assertEquals(990L,result.get("amountMilliunits"));
        assertFalse(result.containsKey("amountFen"));assertNull(order.get("amount_fen"));
        assertNotNull(UUID.fromString(result.get("appAccountToken").toString()));assertEquals("UNPAID",order.get("license_status"));
        assertThrows(ServiceException.class,()->service.preparePayment(7,order.get("order_no").toString()));
        assertThrows(ServiceException.class,()->service.refund(7,order.get("order_no").toString()));
        verify(pay,never()).createGenericAppOrder(anyString(),any(),anyString());
    }
    @Test void verifiedAppleProductionCallerActivatesActualOrderAndRevocationReplayNeverRestores() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        var fact=appleFact(null);var expected=adapter.requireOrder(7L,order.get("order_no").toString());
        adapter.activate(expected,fact);assertEquals("ACTIVE",service.license(7,expected.orderReference()).get("status"));
        assertNull(order.get("payment_fact_id"));assertTrue(adapter.findOrder(fact).isPresent());
        adapter.revoke(adapter.requireOrder(7L,expected.orderReference()),appleFact(300L));
        adapter.activate(adapter.requireOrder(7L,expected.orderReference()),fact);
        assertEquals("REVOKED",service.license(7,expected.orderReference()).get("status"));
        assertThrows(ServiceException.class,()->service.targets(7,expected.orderReference(),null));
        verify(mapper,never()).activate(anyMap());
    }

    @Test @SuppressWarnings("unchecked")
    void actualAppleVerificationServiceCallsActualOrderAdapterAfterSignedResult() throws Exception {
        approved();service.purchase(7,purchase("APPLE_IAP"));
        var store=mock(AppleIapMapper.class);var originals=new HashMap<String,Object>();var transaction=new HashMap<String,Object>();
        when(store.insertOriginal(anyString(),anyString())).thenAnswer(c->{originals.putIfAbsent("identity_hash",c.getArgument(1));return 1;});
        when(store.lockOriginal(anyString())).thenReturn(originals);
        when(store.insertTransaction(anyString(),anyString(),anyString())).thenAnswer(c->{
            transaction.put("original_id",c.getArgument(1));transaction.put("fact_hash",c.getArgument(2));return 1;
        });
        when(store.lockTransaction(anyString())).thenReturn(transaction);
        ObjectProvider<WorkshopAppleOrderPort> ports=mock(ObjectProvider.class);
        when(ports.getIfAvailable()).thenReturn(new WorkshopAppleOrderAdapter(mapper));
        var apple=new WorkshopAppleIapService(config,signed,store,ports,transactions);
        var decoded=new JWSTransactionDecodedPayload().transactionId("1001").originalTransactionId("1000")
                .bundleId(config.getBundleId()).environment(Environment.SANDBOX).productId("paid.edition")
                .appAccountToken(UUID.fromString(order.get("app_account_token").toString())).type(Type.NON_CONSUMABLE)
                .inAppOwnershipType(InAppOwnershipType.PURCHASED).quantity(1).price(990L).currency("USD").purchaseDate(100L).signedDate(200L);
        when(signed.transaction("signed-by-SDK-mock")).thenReturn(decoded);
        String no=order.get("order_no").toString();
        assertEquals("VERIFIED",apple.confirm(7L,no,"signed-by-SDK-mock").get("state"));
        assertEquals("ACTIVE",service.license(7,no).get("status"));
        assertNull(order.get("payment_fact_id"));verify(mapper).activateApple(anyMap());
        verify(signed).transaction("signed-by-SDK-mock");
    }
    @Test void refundBeforePurchaseBindsOriginalAndCrossOwnerOrTokenRebindFails() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        var expected=adapter.requireOrder(7L,order.get("order_no").toString());
        assertThrows(ServiceException.class,()->adapter.requireOrder(8L,expected.orderReference()));
        adapter.revoke(expected,appleFact(300L));adapter.activate(adapter.requireOrder(7L,expected.orderReference()),appleFact(null));
        assertEquals("REVOKED",order.get("license_status"));
        var wrong=appleFact(null);wrong=new WorkshopAppleOrderPort.VerifiedTransaction(wrong.transactionId(),wrong.originalTransactionId(),wrong.bundleId(),
                wrong.environment(),wrong.productId(),UUID.randomUUID(),wrong.type(),wrong.priceMilliunits(),wrong.currency(),wrong.purchaseDate(),wrong.signedDate(),null);
        var conflict=wrong;assertThrows(ServiceException.class,()->adapter.findOrder(conflict));
    }

    @Test void boundOriginalRefundIgnoresChangedSkuAndIncompleteLegacyMetadataWithoutCreatingRights() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        adapter.activate(adapter.requireOrder(7L,order.get("order_no").toString()),appleFact(null));
        config.setSkuPrices(Map.of());order.remove("app_account_token");order.remove("amount_milliunits");version.clear();
        when(mapper.lockBoundAppleOrder(config.getBundleId(),"Sandbox","1000")).thenReturn(order);
        when(mapper.revokeBoundApple(anyMap())).thenAnswer(c->{order.put("state","REFUNDED");order.put("license_status","REVOKED");return 1;});
        adapter.revokeBoundOriginal(config.getBundleId(),"Sandbox","1000",300L);
        adapter.revokeBoundOriginal(config.getBundleId(),"Sandbox","1000",300L);
        assertEquals("REVOKED",order.get("license_status"));verify(mapper,times(1)).revokeBoundApple(anyMap());
        when(mapper.lockBoundAppleOrder(config.getBundleId(),"Sandbox","unknown")).thenReturn(null);
        adapter.revokeBoundOriginal(config.getBundleId(),"Sandbox","unknown",300L);
        verify(mapper,times(1)).activateApple(anyMap()); // Only the original explicitly verified purchase.
    }

    @Test void existingAppleOrderRestoresAfterShelfWithdrawalAndCurrentSkuRemoval() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        var fact=appleFact(null);String no=order.get("order_no").toString();
        adapter.activate(adapter.requireOrder(7L,no),fact);service.withdrawVersion(99,id());config.setSkuPrices(Map.of());products.clear();
        var restored=(Map<?,?>)adapter.restore(adapter.requireOrder(7L,no),fact);
        assertEquals("ACTIVE",restored.get("licenseStatus"));assertEquals(id(),restored.get("purchasedVersionId"));
        created.set(null);assertThrows(ServiceException.class,()->service.purchase(7,purchase("APPLE_IAP")));
        adapter.revokeBoundOriginal(config.getBundleId(),"Sandbox","unknown",300L);
        verify(mapper,never()).revokeBoundApple(anyMap());
    }
    @Test void paidSourceCannotEnterFreePublishReferenceOrSharedCopyButOrdinaryFreeStillCan() {
        var guard=new WorkshopLicenseGuard(mapper);when(mapper.paidSource(80)).thenReturn(List.of(1L));
        assertThrows(ServiceException.class,()->guard.requirePublication(80L,true));
        var memberService=new CmsMemberTemplateServiceImpl();ReflectionTestUtils.setField(memberService,"workshopLicenses",guard);
        assertThrows(ServiceException.class,()->memberService.copyForContentOwner(80L,99L,false));
        var writeGuard=new TopicContentWriteGuard(mock(CmsTopicMapper.class),mock(CmsTopicChapterMapper.class),mock(CmsMemberTemplateMapper.class),mock(IProductRoleService.class),mock(com.chengyinhub.system.service.ISysCategoryService.class));
        ReflectionTestUtils.setField(writeGuard,"workshopLicenses",guard);
        assertThrows(ServiceException.class,()->writeGuard.assertTemplateOwned(80L,99L,true));
        assertDoesNotThrow(()->guard.requirePublication(20L,true));
    }

    @Test void legalSameOwnerReferenceAndForcedCopyRetainLicenseLimitAndRefundFence() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        adapter.activate(adapter.requireOrder(7L,order.get("order_no").toString()),appleFact(null));
        var license=new HashMap<>(order);license.put("installation_owner",7L);license.put("draft_owner",7L);license.put("installation_command","{}");
        when(mapper.lockLicenseForDraft(21L)).thenReturn(license);
        var guard=new WorkshopLicenseGuard(mapper);var members=new CmsMemberTemplateServiceImpl();var rows=mock(CmsMemberTemplateMapper.class);
        ReflectionTestUtils.setField(members,"workshopLicenses",guard);ReflectionTestUtils.setField(members,"cmsMemberTemplateMapper",rows);
        var installed=new CmsMemberTemplate();installed.setId(21L);installed.setMemberId(7L);
        when(rows.selectCmsMemberTemplateById(21L)).thenReturn(installed);
        when(rows.insertCmsMemberTemplate(any())).thenAnswer(c->{((CmsMemberTemplate)c.getArgument(0)).setId(22L);return 1;});
        when(mapper.inheritDraft(anyMap())).thenReturn(1);
        assertEquals(21L,members.copyForContentOwner(21L,7L,false));
        assertEquals(22L,members.copyForContentOwner(21L,7L,true));
        verify(mapper).inheritDraft(argThat(r->r.get("orderNo").equals(order.get("order_no")) && r.get("parentDraftId").equals(21L)));
        assertThrows(ServiceException.class,()->members.copyForContentOwner(21L,8L,false));
        when(mapper.installedCount(order.get("order_no").toString())).thenReturn(2);
        assertThrows(ServiceException.class,()->members.copyForContentOwner(21L,7L,true));
        license.put("license_status","REVOKED");
        assertThrows(ServiceException.class,()->members.copyForContentOwner(21L,7L,false));
    }

    @Test void actualTopicPublicationCallerChecksLicensedCityAndCommercialTerms() {
        approved();service.purchase(7,purchase("APPLE_IAP"));var adapter=new WorkshopAppleOrderAdapter(mapper);
        adapter.activate(adapter.requireOrder(7L,order.get("order_no").toString()),appleFact(null));
        var license=new HashMap<>(order);license.put("installation_owner",7L);license.put("draft_owner",7L);
        when(mapper.lockLicenseForDraft(21L)).thenReturn(license);
        when(mapper.topicReferences(5L)).thenReturn(List.of(Map.of("template_id",21L)));
        var guard=new WorkshopLicenseGuard(mapper);var policy=mock(CommercialTopicPolicy.class);
        ReflectionTestUtils.setField(guard,"commercialTopicPolicy",policy);
        var evaluation=mock(CommercialTopicPolicy.Evaluation.class);when(policy.evaluate(5L)).thenReturn(evaluation);
        var topic=new CmsTopic();topic.setId(5L);topic.setMemberId(7L);topic.setCityCode("310100");
        assertDoesNotThrow(()->guard.requireTheme(topic));
        var publication=new TopicPublicationContentGuard();ReflectionTestUtils.setField(publication,"workshopLicenses",guard);
        topic.setCityCode("110100");assertThrows(ServiceException.class,()->publication.assertPublishReady(topic));
        topic.setCityCode("310100");when(evaluation.isCommercial()).thenReturn(true);
        assertThrows(ServiceException.class,()->publication.assertPublishReady(topic));
        when(evaluation.isCommercial()).thenReturn(false);license.put("license_status","REVOKED");
        assertThrows(ServiceException.class,()->publication.assertPublishReady(topic));
    }
    @Test void withdrawalStopsNewPaymentAndPurchaseButPreservesExactCreateReceipt() {
        approved();var command=purchase("WECHAT_APP");var original=service.purchase(7,command);
        service.withdrawVersion(99,id());assertEquals(original,service.purchase(7,command));
        assertThrows(ServiceException.class,()->service.preparePayment(7,order.get("order_no").toString()));
        created.set(null);assertThrows(ServiceException.class,()->service.purchase(7,command));
    }
    private WorkshopAppleOrderPort.VerifiedTransaction appleFact(Long revoked) {
        return new WorkshopAppleOrderPort.VerifiedTransaction("1001","1000",config.getBundleId(),"Sandbox","paid.edition",
                UUID.fromString(order.get("app_account_token").toString()),"Non-Consumable",990L,"USD",100L,200L,revoked);
    }
}
