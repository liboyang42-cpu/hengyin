package com.chengyinhub.business.service.coupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ITopicCompletionCouponRewardService;
import com.chengyinhub.business.service.impl.TopicCompletionCouponRewardServiceImpl;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.config.TopicCompletionCouponReceiptConfiguration;
import com.chengyinhub.web.controller.api.ApiTopicCompletionCouponReceiptController;
import com.fasterxml.jackson.databind.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real original annotated completion handler + original grant + MyBatis/H2 transaction.
 * The existing FULL qualification caller is not replaced with invented test eligibility.
 * These tests certify grant/receipt transaction behavior only, not the entire gameplay authority.
 */
public class TopicCompletionCouponReceiptTransactionTest {
    CouponTermsSnapshotTransactionTest f; TopicCompletionCouponReceiptMapper mapper;
    TopicCompletionCouponReceiptService receipts; CmsTopicCompletionCouponRewardMapper logs;
    ITopicCompletionCouponRewardService completion; TopicCompletionCouponRewardServiceImpl target; MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        f=new CouponTermsSnapshotTransactionTest();f.setup();
        f.jdbc.execute("CREATE TABLE cms_topic_completion_coupon_reward_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,topic_id BIGINT NOT NULL,member_id BIGINT NOT NULL,coupon_id BIGINT NOT NULL,created_at TIMESTAMP,receipt_contract_version SMALLINT NULL,UNIQUE(topic_id,member_id))");
        f.jdbc.execute("CREATE TABLE cms_topic_completion_coupon_receipt(grant_log_id BIGINT PRIMARY KEY,topic_id BIGINT NOT NULL,member_id BIGINT NOT NULL,coupon_id BIGINT NOT NULL,history_id BIGINT NOT NULL UNIQUE,granted_at TIMESTAMP(3) NOT NULL)");
        for(String name:new String[]{"CmsTopicCompletionCouponRewardMapper","TopicCompletionCouponReceiptMapper"}) {
            String path="/mapper/business/"+name+".xml";try(InputStream in=getClass().getResourceAsStream(path)){assertNotNull(in);new XMLMapperBuilder(in,f.sql.getConfiguration(),path,f.sql.getConfiguration().getSqlFragments()).parse();}
        }
        mapper=f.sql.getMapper(TopicCompletionCouponReceiptMapper.class);logs=f.sql.getMapper(CmsTopicCompletionCouponRewardMapper.class);
        receipts=new TopicCompletionCouponReceiptService(mapper,f.legacy,f.db);
        target=new TopicCompletionCouponRewardServiceImpl();ReflectionTestUtils.setField(target,"rewardMapper",logs);
        ReflectionTestUtils.setField(target,"smsCouponHistoryService",f.legacy);ReflectionTestUtils.setField(target,"receiptService",receipts);ReflectionTestUtils.setField(target,"receiptProtocol",receipts);
        ProxyFactory proxy=new ProxyFactory(target);proxy.setInterfaces(ITopicCompletionCouponRewardService.class);
        proxy.addAdvice(new TransactionInterceptor(f.manager,new AnnotationTransactionAttributeSource()));completion=(ITopicCompletionCouponRewardService)proxy.getProxy();
        mvc=MockMvcBuilders.standaloneSetup(new ApiTopicCompletionCouponReceiptController(receipts)).build();SecurityContextHolder.clearContext();
    }
    @AfterEach void close(){SecurityContextHolder.clearContext();f.close();}
    long log(){return f.jdbc.queryForObject("SELECT id FROM cms_topic_completion_coupon_reward_log WHERE topic_id=100 AND member_id=7",Long.class);}
    int count(String table){return f.count(table);}
    void empty(){assertEquals(0,count("cms_topic_completion_coupon_reward_log"));assertEquals(0,count("cms_topic_completion_coupon_receipt"));assertEquals(0,count("sms_coupon_history"));assertEquals(0L,f.coupons.selectSmsCouponById(8L).getReceiveCount());}
    void login(long inner,long outer){AppUser a=new AppUser();a.setUserId(inner);LoginAppUser u=new LoginAppUser(outer,a);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,"synthetic",Collections.emptyList()));}
    @Test void exactOriginalCompletionGrantReturnsRecordedHistoryNotLatestGuess(){
        long unrelated=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));assertTrue(completion.grantOnce(100L,7L,8L));
        Map<String,Object> r=receipts.owned(7,log());long history=Long.parseLong((String)r.get("historyId"));assertNotEquals(unrelated,history);
        long later=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));assertNotEquals(later,history);assertEquals(r,receipts.owned(7,log()));
        assertEquals("RECORDED",r.get("recording"));assertEquals("NOT_PROVIDED",r.get("termsAvailability"));assertEquals(100L,r.get("validFrom"));assertEquals(9000L,r.get("validUntil"));
    }
    @Test void duplicateCompletionDoesNotGrantOrAppendTwice(){assertTrue(completion.grantOnce(100L,7L,8L));long old=log();assertFalse(completion.grantOnce(100L,7L,8L));assertEquals(old,log());assertEquals(1,count("sms_coupon_history"));assertEquals(1,count("cms_topic_completion_coupon_receipt"));assertEquals(1L,f.coupons.selectSmsCouponById(8L).getReceiveCount());}
    @Test void missingNewLogCannotGrantThroughReceiptHook(){new TransactionTemplate(f.manager).execute(s->{assertThrows(IllegalStateException.class,()->receipts.grantForNewCompletion(100,7,8));return null;});empty();}
    @Test void missingActiveTransactionCannotGrant(){assertThrows(IllegalStateException.class,()->receipts.grantForNewCompletion(100,7,8));empty();}
    @Test void unrelatedDatasourceTransactionCannotGrant(){JdbcDataSource other=new JdbcDataSource();other.setURL("jdbc:h2:mem:foreign_"+UUID.randomUUID());TopicCompletionCouponReceiptService foreign=new TopicCompletionCouponReceiptService(mapper,f.legacy,other);new TransactionTemplate(f.manager).execute(s->{assertThrows(IllegalStateException.class,()->foreign.grantForNewCompletion(100,7,8));return null;});empty();}
    @Test void stoppedOrManualInvalidSkipsWithoutLogAsOriginalContract(){for(int status:new int[]{3,4}){f.jdbc.update("UPDATE sms_coupon SET status=? WHERE id=8",status);assertFalse(completion.grantOnce(100L,7L,8L));empty();}}
    @Test void exhaustedStockRollsBackCompletionSlot(){f.jdbc.update("UPDATE sms_coupon SET publish_count=1,receive_count=1 WHERE id=8");assertThrows(IllegalStateException.class,()->completion.grantOnce(100L,7L,8L));assertEquals(0,count("cms_topic_completion_coupon_reward_log"));assertEquals(0,count("sms_coupon_history"));assertEquals(0,count("cms_topic_completion_coupon_receipt"));assertEquals(1L,f.coupons.selectSmsCouponById(8L).getReceiveCount());}
    @Test void receiptFailureRollsBackLogHistoryAndOriginalStock(){f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_receipt ADD CONSTRAINT reject_receipt CHECK(history_id<0)");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();}
    @Test void historyFailureRollsBackLogAndStock(){f.jdbc.execute("ALTER TABLE sms_coupon_history ADD CONSTRAINT reject_history CHECK(coupon_id<0)");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();}
    @Test void parentGameplayRollbackAlsoRollsBackAllGrantFacts(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.manager).execute(s->{assertTrue(completion.grantOnce(100L,7L,8L));throw new IllegalStateException("synthetic parent failure");}));empty();}
    @Test void missingDefinitionRollsBackSlotRatherThanReportingGrant(){assertThrows(IllegalStateException.class,()->completion.grantOnce(100L,7L,999L));empty();}
    @Test void oldLogWithoutAssociationIsNeverBackfilledOrReissued(){logs.insertIgnore(100L,7L,8L);assertFalse(completion.grantOnce(100L,7L,8L));assertNull(receipts.owned(7,log()));assertEquals(0,count("sms_coupon_history"));assertEquals(0,count("cms_topic_completion_coupon_receipt"));}
    @Test void defaultAbsentHookKeepsOriginalGrantOnVerifiedOldSchema(){
        f.jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");
        f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");
        ReflectionTestUtils.setField(target,"receiptService",null);
        assertTrue(completion.grantOnce(100L,7L,8L));assertEquals(1,count("sms_coupon_history"));
        boolean installed=new TransactionTemplate(f.manager).execute(s->receipts.protocolInstalled());assertFalse(installed);
        assertEquals(1,count("cms_topic_completion_coupon_reward_log"));assertEquals(1L,f.coupons.selectSmsCouponById(8L).getReceiveCount());
    }
    @Test void approvedLogRemovalAndRecompletionPreserveDistinctPastReceipt(){assertTrue(completion.grantOnce(100L,7L,8L));long first=log();Map<String,Object> old=receipts.owned(7,first);assertEquals(1,logs.deleteGrant(100L,7L));assertTrue(completion.grantOnce(100L,7L,8L));long second=log();assertNotEquals(first,second);assertNotEquals(old.get("historyId"),receipts.owned(7,second).get("historyId"));assertEquals(old,receipts.owned(7,first));assertEquals(2,count("cms_topic_completion_coupon_receipt"));}
    @Test void ownerFilterAndHistoryCorrelationPreventForeignProjection(){assertTrue(completion.grantOnce(100L,7L,8L));long id=log();assertNull(receipts.owned(9,id));f.jdbc.update("UPDATE sms_coupon_history SET member_id=9");assertNull(receipts.owned(7,id));assertNull(receipts.owned(9,id));}
    @Test void originalHistoryUseFactsAreReadBackWithoutChangingRedemptionRules(){assertTrue(completion.grantOnce(100L,7L,8L));long id=log(),history=Long.parseLong((String)receipts.owned(7,id).get("historyId"));f.jdbc.update("UPDATE sms_coupon_history SET start_time=NULL,end_time=NULL WHERE id=?",history);Date at=new Date(2000);assertEquals(1,f.histories.claimUnusedCoupon(history,at));Map<String,Object> used=receipts.owned(7,id);assertEquals(1,used.get("useStatus"));assertEquals(2000L,used.get("usedAt"));assertEquals(0,f.histories.claimUnusedCoupon(history,new Date(3000)));assertEquals(2000L,receipts.owned(7,id).get("usedAt"));}
    @Test void concurrentDuplicateCompletionHasExactlyOneHistoryAndReceipt()throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{Future<Boolean>a=pool.submit(()->{start.await();return completion.grantOnce(100L,7L,8L);});Future<Boolean>b=pool.submit(()->{start.await();return completion.grantOnce(100L,7L,8L);});start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,count("sms_coupon_history"));assertEquals(1,count("cms_topic_completion_coupon_receipt"));}finally{pool.shutdownNow();}}
    @Test void pagePreservesExactGrantIdsAndCursorWithoutAnotherOwner(){completion.grantOnce(100L,7L,8L);completion.grantOnce(101L,7L,8L);completion.grantOnce(102L,9L,8L);Map<String,Object> first=receipts.ownedPage(7,1,null);assertNotNull(first.get("nextCursor"));Map<String,Object> second=receipts.ownedPage(7,1,Long.valueOf((String)first.get("nextCursor")));assertNull(second.get("nextCursor"));assertEquals(2,((List<?>)receipts.ownedPage(7,20,null).get("items")).size());}
    @Test void controllerDerivesBeneficiaryFromNestedPrincipalAndExcludesCouponCodes()throws Exception{completion.grantOnce(100L,7L,8L);login(7,9);MvcResult result=mvc.perform(get("/api/rewards/topic-completion-coupons").param("limit","20")).andExpect(status().isOk()).andReturn();assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));JsonNode data=new ObjectMapper().readTree(result.getResponse().getContentAsString()).get("data");assertEquals(1,data.get("items").size());assertFalse(result.getResponse().getContentAsString().contains("couponCode"));assertFalse(result.getResponse().getContentAsString().contains("PRIVATE-DEFINITION-CODE"));}
    @Test void controllerForeignOrMissingReceiptIsIndistinguishable404()throws Exception{completion.grantOnce(100L,7L,8L);login(9,9);mvc.perform(get("/api/rewards/topic-completion-coupons/"+log())).andExpect(status().isNotFound());mvc.perform(get("/api/rewards/topic-completion-coupons/9999")).andExpect(status().isNotFound());}
    @Test void controllerRejectsAnonymousAndNonAppPrincipal()throws Exception{mvc.perform(get("/api/rewards/topic-completion-coupons").param("limit","20")).andExpect(status().isUnauthorized());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","synthetic",Collections.emptyList()));mvc.perform(get("/api/rewards/topic-completion-coupons/1")).andExpect(status().isUnauthorized());}
    @Test void controllerRejectsActorAliasesDuplicateAndMalformedPagination()throws Exception{login(7,7);for(String key:new String[]{"memberId","owner","actor","topicId","historyId"})mvc.perform(get("/api/rewards/topic-completion-coupons").param("limit","20").param(key,"7")).andExpect(status().isBadRequest());mvc.perform(get("/api/rewards/topic-completion-coupons").param("limit","20","21")).andExpect(status().isBadRequest());for(String v:new String[]{"0","01","51"})mvc.perform(get("/api/rewards/topic-completion-coupons").param("limit",v)).andExpect(status().isBadRequest());mvc.perform(get("/api/rewards/topic-completion-coupons/9223372036854775808")).andExpect(status().isBadRequest());mvc.perform(get("/api/rewards/topic-completion-coupons/1").param("memberId","7")).andExpect(status().isBadRequest());}
    @Test void controllerHasNoIssuanceOrRedeemRoute()throws Exception{login(7,7);mvc.perform(post("/api/rewards/topic-completion-coupons")).andExpect(status().isMethodNotAllowed());mvc.perform(post("/api/rewards/topic-completion-coupons/1")).andExpect(status().isMethodNotAllowed());empty();}
    @Test void receiptProfileIsOffByDefault(){try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){context.register(TopicCompletionCouponReceiptConfiguration.class,ApiTopicCompletionCouponReceiptController.class);context.refresh();assertTrue(context.getBeansOfType(TopicCompletionCouponReceiptService.class).isEmpty());assertTrue(context.getBeansOfType(ApiTopicCompletionCouponReceiptController.class).isEmpty());}}
    @Test void originalMetadataMaintenanceTouchesOnlyOwnUnusedExpiredUndeletedStatus() {
        long own=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));
        long foreign=f.legacy.grantCouponWithReceipt(9L,f.coupons.selectSmsCouponById(8L));
        long used=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));
        long deleted=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));
        long future=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));
        f.jdbc.update("UPDATE sms_coupon_history SET use_status=1 WHERE id=?",used);
        f.jdbc.update("UPDATE sms_coupon_history SET del_flag=2 WHERE id=?",deleted);
        f.jdbc.update("UPDATE sms_coupon_history SET end_time=? WHERE id=?",new java.sql.Timestamp(System.currentTimeMillis()+86400000),future);
        SmsCouponHistory before=f.histories.selectSmsCouponHistoryById(own);
        assertEquals(1,f.legacy.updateExpiredCouponsByMemberId(7L));
        SmsCouponHistory after=f.histories.selectSmsCouponHistoryById(own);assertEquals(2,after.getUseStatus());
        assertEquals(before.getStartTime(),after.getStartTime());assertEquals(before.getEndTime(),after.getEndTime());
        assertEquals(before.getCouponCode(),after.getCouponCode());assertEquals(before.getGetTime(),after.getGetTime());
        assertEquals(0,f.histories.selectSmsCouponHistoryById(foreign).getUseStatus());assertEquals(1,f.histories.selectSmsCouponHistoryById(used).getUseStatus());
        assertEquals(0,f.histories.selectSmsCouponHistoryById(deleted).getUseStatus());assertEquals(0,f.histories.selectSmsCouponHistoryById(future).getUseStatus());
        assertEquals(5L,f.coupons.selectSmsCouponById(8L).getReceiveCount());assertEquals("Original title",f.coupons.selectSmsCouponById(8L).getName());
    }
    @Test void legacyMetadataMaintenanceWithoutParentTransactionIsDurableAndIdempotent() {
        long id=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));assertEquals(1,f.legacy.updateExpiredCouponsByMemberId(7L));
        assertEquals(2,f.histories.selectSmsCouponHistoryById(id).getUseStatus());assertEquals(0,f.legacy.updateExpiredCouponsByMemberId(7L));
        // The original MVC method has no transaction: a later read failure cannot roll this back.
        assertEquals(2,f.jdbc.queryForObject("SELECT use_status FROM sms_coupon_history WHERE id=?",Integer.class,id));
    }
    @Test void legacyMetadataMaintenanceJoinsExistingTransactionWhenProvided() {
        long id=f.legacy.grantCouponWithReceipt(7L,f.coupons.selectSmsCouponById(8L));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.manager).execute(s->{assertEquals(1,f.legacy.updateExpiredCouponsByMemberId(7L));throw new IllegalStateException("synthetic parent rollback");}));
        assertEquals(0,f.histories.selectSmsCouponHistoryById(id).getUseStatus());
    }

}
