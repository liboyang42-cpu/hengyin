package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.TopicSettlementCashBasis;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** G real close/guard/recovery owner, actual registration repair helper/XML and B typed durable fixtures.
 * Topic adapter executes real row lock/CAS. No SDK/payment-provider verification, complete A callback or money allocation claim. */
class Wp04PaidHalfStateMySqlIT {
    JdbcTemplate admin,db;
    DriverManagerDataSource source;
    String createdDatabase;
    TransactionTemplate tx;
    CmsRegistrationMapper registrations;
    CoopSettlementEvidenceMapper evidence;
    CoopSettlementOriginalMapper originals;
    WechatVerifiedPaymentFactService facts;
    WechatVerifiedPaymentFact.Reference reference;
    CoopClosedPaymentRecovery recovery;
    WxPaymentCallbackOutboxServiceImpl outbox;
    CmsTopicServiceImpl settlement;
    CmsRegistrationServiceImpl payment;
    ICoopInviteService deposits;

    @BeforeEach void originalSchemaAndTenYuanPaidHalfState() throws Exception {
        originalSchemaAndTenYuanPaidHalfState(true);
    }
    void originalSchemaAndTenYuanPaidHalfState(boolean withStandardInbox) throws Exception {
        assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1",System.getenv("WP04_PAID_HALF_STATE_DDL_APPROVED"));
        for(String key:List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX")) assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+key));
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        assertTrue(MybatisItDatabase.port().matches("[0-9]+"));int port=Integer.parseInt(MybatisItDatabase.port());assertTrue(port>0&&port<=65535);
        assertFalse(MybatisItDatabase.user().isBlank());assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
        String database=MybatisItDatabase.databaseName("g_half_"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        assertTrue(database.matches("chengyin_mybatis_g_half_[a-f0-9]{12}[A-Za-z0-9_]*")&&database.length()<=64);
        String prefix="jdbc:mysql://"+MybatisItDatabase.host()+":"+port+"/",params="?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin=new JdbcTemplate(new DriverManagerDataSource(prefix+params,MybatisItDatabase.user(),MybatisItDatabase.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `"+database+"`");createdDatabase=database;
        source=new DriverManagerDataSource(prefix+database+params,MybatisItDatabase.user(),MybatisItDatabase.password());db=new JdbcTemplate(source);
        assertEquals(createdDatabase,db.queryForObject("SELECT DATABASE()",String.class));
        Wp04OriginalRefundMySqlIT.prerequisites(source);
        P0AcceptanceSchema.baseTables(db,"wx_payment_callback_outbox");
        P0AcceptanceSchema.migrations(source,"migration_20260930_registration_first_successful_scan.sql",Wp04OriginalRefundMySqlIT.MIGRATION);
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(source,"CmsRegistrationMapper","CoopSettlementEvidenceMapper","CoopSettlementOriginalMapper",
                "CoopSettlementCashEvidenceMapper","WechatVerifiedPaymentFactMapper","WxPaymentCallbackOutboxMapper");
        registrations=sql.getMapper(CmsRegistrationMapper.class);evidence=sql.getMapper(CoopSettlementEvidenceMapper.class);originals=sql.getMapper(CoopSettlementOriginalMapper.class);
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);tx.setTimeout(15);
        facts=WechatVerifiedFactTestSupport.realFacts(source);
        db.update("INSERT INTO ums_member(id,balance) VALUES(7,0),(77,0)");
        db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement) VALUES(11,'G paid half-state',1,7,0)");
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time) VALUES(1,'REG1',1,11,21,10,77,1,10,10,10,10,1,1,0,1,'2026-10-01 08:00:00')");
        reference=facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original","REG1","REG1","REG1","WXTX1","WXTX1","JSAPI","CNY",1000,1000,Instant.ofEpochSecond(1),null,"SUCCESS"));
        db.update("UPDATE cms_registration SET payment_status=2 WHERE id=1");
        String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:WXTX1:SUCCESS");
        if (withStandardInbox) {
            db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(1,UNHEX(?),1,1,'SUCCESS',0)",hash);
            facts.bindNewInbox(1,hash,reference,true);
        }
        CmsTopicMapper topics=mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call->db.queryForObject("SELECT id,is_settlement,product_type FROM cms_topic WHERE id=11 FOR UPDATE",(rs,n)->{
            CmsTopic row=new CmsTopic();row.setId(rs.getLong(1));row.setIsSettlement(rs.getInt(2));row.setProductType(rs.getInt(3));return row;
        }));
        when(topics.claimTopicForSettlement(11L)).thenAnswer(call->db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11 AND is_settlement=0"));
        settlement=new CmsTopicServiceImpl();ReflectionTestUtils.setField(settlement,"cmsTopicMapper",topics);ReflectionTestUtils.setField(settlement,"cmsRegistrationMapper",registrations);
        ReflectionTestUtils.setField(settlement,"coopSettlementEvidenceMapper",evidence);ReflectionTestUtils.setField(settlement,"coopSettlementOriginalMapper",originals);ReflectionTestUtils.setField(settlement,"coopOrderMapper",mock(CoopOrderMapper.class));
        ReflectionTestUtils.setField(settlement, "coopInviteMapper", mock(com.chengyinhub.business.mapper.CoopInviteMapper.class));
        deposits=mock(ICoopInviteService.class);ReflectionTestUtils.setField(settlement,"coopInviteService",deposits);
        payment=new CmsRegistrationServiceImpl();ReflectionTestUtils.setField(payment,"cmsRegistrationMapper",registrations);
        outbox=new WxPaymentCallbackOutboxServiceImpl();ReflectionTestUtils.setField(outbox,"mapper",sql.getMapper(WxPaymentCallbackOutboxMapper.class));
        CoopClosedPaymentRecovery target=new CoopClosedPaymentRecovery(topics,originals,sql.getMapper(CoopSettlementCashEvidenceMapper.class),facts,outbox);
        ReflectionTestUtils.setField(target,"recoveryRegistrations",registrations);
        ReflectionTestUtils.setField(target,"recoveryActivities",mock(CmsActivityMapper.class));
        ProxyFactory proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        recovery=(CoopClosedPaymentRecovery)proxy.getProxy();
        @SuppressWarnings("unchecked") ObjectProvider<CoopClosedPaymentRecovery> self=mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(recovery);ReflectionTestUtils.setField(target,"self",self);
    }
    @AfterEach void dropOnlyOwnCreatedSchema() {
        if(createdDatabase!=null){assertTrue(createdDatabase.matches("chengyin_mybatis_g_half_[a-f0-9]{12}[A-Za-z0-9_]*"));admin.execute("DROP DATABASE `"+createdDatabase+"`");
            assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,createdDatabase));createdDatabase=null;}
    }

    @Test void paidHalfStateCannotCommitZeroCloseAndOriginalRepairRestoresTenYuanEligibility() {
        IllegalStateException error=assertThrows(IllegalStateException.class,()->tx.execute(status->ReflectionTestUtils.invokeMethod(settlement,"settleTopic",newTopic(),new SettlementRule())));
        assertTrue(error.getMessage().contains("PAID_REGISTRATION_HALF_STATE_UNRESOLVED"));
        assertEquals(0,topicClosed());assertHalfState();assertEquals(0,count("coop_settlement"));verifyNoInteractions(deposits);
        tx.executeWithoutResult(status->{lockTopic();CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(1L);
            CmsRegistration repaired=ReflectionTestUtils.invokeMethod(payment,"repairHistoricalPaidHalfState",current,"WXTX1",new BigDecimal("10.00"),new Date());
            assertEquals(2,repaired.getRegistrationStatus());
            TopicSettlementCashBasis basis=TopicSettlementCashBasis.fromLockedRows(11L,evidence.selectTopicCashRowsForUpdate(11L));assertEquals(0,new BigDecimal("10.00").compareTo(basis.getTotalSales()));});
        assertEquals(0,topicClosed());assertEquals(2,registrationStatus());
        RuntimeException positiveGuard=assertThrows(RuntimeException.class,()->tx.execute(status->ReflectionTestUtils.invokeMethod(settlement,"settleTopic",newTopic(),new SettlementRule())));
        assertTrue(positiveGuard.getMessage().contains("NON_COOP_TOPIC_CASH_SETTLEMENT_UNPROVEN"),"修复后10元仍须原合作资格，不能继续走零销售");
        assertEquals(0,topicClosed());assertEquals(0,count("coop_settlement"));
    }

    @Test void alreadyClosedHalfStateRetainsOriginalSuccessAndManualIdentityWithoutRepairOrDone() {
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11"); // Historic zero-close state, not an action of the fixed close path.
        assertFalse(recovery.retainClosedSuccess(1L,reference));assertFalse(recovery.retainClosedSuccess(1L,reference));
        assertRecoveryIdentity();assertHalfState();assertEquals(0,inboxState());
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            recovery.requireCashAdmission(1L,reference);
            repairAndMarkDone(); // Must never run after the guard.
        }));
        assertRecoveryIdentity();assertHalfState();assertEquals(0,inboxState());assertEquals(0,count("coop_settlement"));
        assertTrue(tx.execute(status->outbox.claimForRetry(10)).isEmpty(),"人工资金恢复不能进入自动派发");
    }

    @Test void closeBetweenObservationAndMutationRechecksAndRetainsOnlyAfterRollbackReleasesTopicLock() {
        assertTrue(recovery.retainClosedSuccess(1L,reference));assertEquals(0,count("wx_payment_callback_outbox"));
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{recovery.requireCashAdmission(1L,reference);repairAndMarkDone();}));
        assertRecoveryIdentity();assertHalfState();assertEquals(0,inboxState());assertEquals(0,count("coop_settlement"));
    }

    @Test void historicalZeroCloseThenLateRepairLeavesTenYuanWithoutCashHeadersAndIsExplicitlyRetained() {
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11"); // Seed known old bad close, which fixed code now refuses.
        tx.executeWithoutResult(status->{lockTopic();repairAndMarkDone();});
        assertEquals(2,registrationStatus());assertEquals(2,inboxState());assertEquals(0,count("coop_settlement"));
        assertFalse(recovery.retainClosedSuccess(1L,reference));assertRecoveryIdentity();
        assertEquals(1,topicClosed());assertEquals(2,inboxState(),"不删原DONE或伪造原head回填");
        assertEquals(0,count("coop_settlement_original_component"));assertEquals(0,count("coop_settlement_cash_close"));
        assertEquals(0,new BigDecimal("10.00").compareTo(registrations.selectCmsRegistrationByIdForUpdate(1L).getWechatPaymentAmount()));
    }
    @Test void closedParentCompleteButChildHalfStateRetainsPayerFactDespiteExistingCashHead() {
        parentCompleteChildHalfState();
        assertFalse(recovery.retainClosedSuccess(1L,reference));
        assertRecoveryIdentity();assertEquals(2,registrationStatus());
        assertEquals(1,db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=2",Integer.class));
        assertEquals(1,count("coop_settlement"));assertEquals(0,inboxState());
        assertEquals(1L,db.queryForObject("SELECT biz_id FROM wx_payment_callback_outbox",Long.class),"原付款父单身份，不改传从单");
    }
    void parentCompleteChildHalfState(){
        db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=11");
        db.update("UPDATE cms_registration SET parent_order_no='GROUP1',registration_status=2,transaction_id='WXTX1' WHERE id=1");
        db.update("INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time,transaction_id) VALUES(2,'REG2','GROUP1',1,11,21,0,77,1,0,0,0,0,2,1,0,1,'2026-10-01 08:00:00','WXTX1')");
        db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,amount,status) VALUES(12,11,7,'MEMBER','INITIATOR_MARGIN','initiator',10,0)");
    }
    void repairAndMarkDone(){
        CmsRegistration current=registrations.selectCmsRegistrationByIdForUpdate(1L);
        CmsRegistration repaired=ReflectionTestUtils.invokeMethod(payment,"repairHistoricalPaidHalfState",current,"WXTX1",new BigDecimal("10.00"),new Date());
        assertEquals(2,repaired.getRegistrationStatus());
        assertEquals(0,new SettlementEntryServiceImpl().recordOnPayment(repaired),"原主题支付入账确实跳过，不能声称这会补现金head");
        db.update("UPDATE wx_payment_callback_inbox SET state=2 WHERE id=1"); // Explicit old DONE fixture, not full callback acceptance.
    }
    void assertRecoveryIdentity(){
        assertEquals(1,count("wx_payment_callback_outbox"));
        Map<String,Object> row=db.queryForMap("SELECT biz_type,biz_id,idempotency_key,money_affecting,state FROM wx_payment_callback_outbox");
        assertEquals(1,((Number)row.get("biz_type")).intValue());assertEquals(1,((Number)row.get("biz_id")).longValue());
        assertEquals("COOP_PAID_HALF_LATE_SUCCESS:"+reference.id()+":"+reference.hash(),row.get("idempotency_key"));
        Object moneyAffecting=row.get("money_affecting");
        assertTrue(Boolean.TRUE.equals(moneyAffecting) || moneyAffecting instanceof Number && ((Number)moneyAffecting).intValue()==1);
        assertEquals(4,((Number)row.get("state")).intValue());
        assertEquals(reference,facts.requireVerified(reference).reference());assertEquals(1,count("wx_payment_callback_fact_link"));
        assertEquals(0,count("ums_member_balance_detail"));assertEquals(0,count("ums_member_settlement_debt"));
    }
    void assertHalfState(){assertEquals(2,db.queryForObject("SELECT payment_status FROM cms_registration WHERE id=1",Integer.class));assertEquals(1,registrationStatus());assertEquals(0,db.queryForObject("SELECT verification_status FROM cms_registration WHERE id=1",Integer.class));}
    int registrationStatus(){return db.queryForObject("SELECT registration_status FROM cms_registration WHERE id=1",Integer.class);}
    int topicClosed(){return db.queryForObject("SELECT is_settlement FROM cms_topic WHERE id=11",Integer.class);}
    int inboxState(){return db.queryForObject("SELECT state FROM wx_payment_callback_inbox WHERE id=1",Integer.class);}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    void lockTopic(){db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);}
    CmsTopic newTopic(){CmsTopic row=new CmsTopic();row.setId(11L);return row;}
}
