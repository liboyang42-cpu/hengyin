package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.WxPaymentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackInboxService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real fact tables/XML/AOP and existing inbox. Facts-only proof; original registration mutation+DONE is tested separately. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatVerifiedPaymentFactMySqlIT {
    private final String suite="bwxfact_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private WechatVerifiedPaymentFactService facts;
    private WxPaymentCallbackInboxServiceImpl inbox;
    private WxPaymentCallbackMutationService mutations;
    @BeforeAll void schemaAndRealMappers() throws Exception {
        assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1",System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        source=P0AcceptanceSchema.dataSource(suite); db=new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite),db.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(db.queryForObject("SELECT VERSION()",String.class)));
        P0AcceptanceSchema.baseTables(db,"cms_registration");
        P0AcceptanceSchema.migrations(source,"migration_20261023_wx_pay_order_intent.sql","migration_wx_payment_callback_inbox_outbox_20260725.sql",
                "migration_20261024_wx_verified_payment_fact.sql");
        facts=WechatVerifiedFactTestSupport.realFacts(source);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/WxPaymentCallbackInboxMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(WxPaymentCallbackInboxMapper.class);
        var claim=new WxPaymentCallbackClaimService(); ReflectionTestUtils.setField(claim,"mapper",mapper);
        inbox=new WxPaymentCallbackInboxServiceImpl();ReflectionTestUtils.setField(inbox,"mapper",mapper);
        ReflectionTestUtils.setField(inbox,"verifiedFacts",facts);ReflectionTestUtils.setField(inbox,"claimService",transactional(claim));
        mutations=mock(WxPaymentCallbackMutationService.class);ReflectionTestUtils.setField(inbox,"mutationService",mutations);
        ReflectionTestUtils.setField(inbox,"alarmService",mock(AlarmService.class));inbox=transactional(inbox);
    }
    @BeforeEach void clearOnlyOwnedScratch() {
        for (String table:List.of("wx_payment_callback_fact_link","wx_verified_payment_fact_rejection",
                "wx_verified_payment_fact","wx_payment_callback_inbox","wx_payment_callback_outbox")) db.update("DELETE FROM "+table);
        db.update("DELETE FROM wx_pay_order_intent");db.update("DELETE FROM cms_registration");
        for(int id:List.of(1,2)) {
            db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                    +"original_amount,total_amount,payable_amount,wechat_payment_amount,payment_status,registration_status,pay_expire_time)"
                    +" VALUES(?,?,1,23,9,10.00,7,10.00,10.00,10.00,10.00,1,1,DATE_ADD(NOW(),INTERVAL 1 HOUR))",id,"REG"+id);
            db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) VALUES('merchant',?,?,'REGISTRATION',?,1,'wx-original','APP','CNY',1000,?,'','','https://example.test/notify')",
                    "REG"+id,"REG"+id,id,"a".repeat(64));
        }
        clearInvocations(mutations);
    }
    @AfterAll void cleanup() {
        if (db!=null) { assertTrue(MybatisItDatabase.databaseName(suite).matches("chengyin_mybatis_bwxfact_[a-z0-9_]+"));P0AcceptanceSchema.drop(db,suite); }
    }
    @Test void duplicatePaymentAndRefundFactsReadBackTheExactOriginalIdentity() {
        var payment=payment("TX1","REG1",1000); var reference=facts.recordVerified(payment);
        assertEquals(reference,facts.recordVerified(payment)); assertEquals(payment,facts.requireVerified(reference).facts());
        var refund=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant","wx-original","REG1","REG1",
                "RF1","WXRF1","TX1","APP","CNY",1000,500,Instant.parse("2026-10-24T01:00:00Z"),"a".repeat(64),"REFUND");
        var refundReference=facts.recordVerified(refund); assertEquals(refundReference,facts.recordVerified(refund));
        assertEquals(refund,facts.requireVerified(refundReference).facts());
        assertEquals(2,count("wx_verified_payment_fact")); assertEquals(0,count("wx_verified_payment_fact_rejection"));
        assertEquals("TX1",db.queryForObject("SELECT transaction_id FROM wx_verified_payment_fact WHERE id=?",String.class,refundReference.id()));
    }
    @Test void conflictsRetainOriginalFactsAndRejectionEvidenceAfterTheRejectedTransactionReturns() {
        var original=facts.recordVerified(payment("TX1","REG1",1000));
        for (var changed:List.of(payment("TX1","REG1",1001),payment("TX1","REG2",1000),payment("TX2","REG1",1000))) {
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->facts.recordVerified(changed));
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->facts.recordVerified(changed));
        }
        assertEquals(1,count("wx_verified_payment_fact"));assertEquals(3,count("wx_verified_payment_fact_rejection"));
        assertEquals(1000,facts.requireVerified(original).facts().totalFen());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM wx_verified_payment_fact_rejection WHERE original_fact_id=?",Integer.class,original.id()));
    }
    @Test void concurrentSameFactReplayHasOnePermanentIdentity() throws Exception {
        var start=new CountDownLatch(1);List<Future<WechatVerifiedPaymentFact.Reference>> results=new ArrayList<>();
        try (var threads=Executors.newFixedThreadPool(4)) {
            for (int i=0;i<4;i++) results.add(threads.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return facts.recordVerified(payment("TX1","REG1",1000));}));
            start.countDown();var first=results.get(0).get(20,TimeUnit.SECONDS);
            for(var future:results) assertEquals(first,future.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,count("wx_verified_payment_fact"));assertEquals(0,count("wx_verified_payment_fact_rejection"));
    }
    @Test void doneReplayRequiresTheOriginalFactAndLegacyUnboundDoneIsNotUpgraded() {
        var first=facts.recordVerified(payment("TX1","REG1",1000));var other=facts.recordVerified(payment("TX2","REG2",1000));
        assertTrue(inbox.registerVerified("PAY:TX1:SUCCESS",1,11L,"SUCCESS",first));
        assertFalse(inbox.registerVerified("PAY:TX1:SUCCESS",1,11L,"SUCCESS",first));
        // Set up an existing DONE receipt only; this case does not simulate or claim a business mutation commit.
        db.update("UPDATE wx_payment_callback_inbox SET state=2");
        var mutation=mock(IWxPaymentCallbackInboxService.Mutation.class);
        assertTrue(inbox.processVerified("PAY:TX1:SUCCESS",first,mutation));
        assertFalse(inbox.processVerified("PAY:TX1:SUCCESS",other,mutation));
        assertThrows(IWxPaymentCallbackInboxService.IdentityConflictException.class,
                ()->inbox.registerVerified("PAY:TX1:SUCCESS",1,11L,"SUCCESS",other));
        assertTrue(inbox.register("LEGACY:SUCCESS",1,12L,"SUCCESS"));
        db.update("UPDATE wx_payment_callback_inbox SET state=2");
        assertThrows(IWxPaymentCallbackInboxService.IdentityConflictException.class,
                ()->inbox.registerVerified("LEGACY:SUCCESS",1,12L,"SUCCESS",first));
        assertEquals(1,count("wx_payment_callback_fact_link"));assertEquals(2,count("wx_verified_payment_fact_rejection"));
        verifyNoInteractions(mutations,mutation);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    @SuppressWarnings("unchecked") private <T> T transactional(T target) {
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        return (T)proxy.getProxy();
    }
    private static WechatVerifiedPaymentFact payment(String tx,String order,long fen) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",order,order,order,tx,tx,
                "APP","CNY",fen,fen,Instant.parse("2026-10-24T00:00:00Z"),"a".repeat(64),"SUCCESS");
    }
}
