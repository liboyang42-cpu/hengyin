package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.impl.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.chengyinhub.business.service.impl.WechatPayTestWiring;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 本地MySQL8原Mapper/事务，微信与通知均替身，唯一scratch用后删除。 */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoopDepositRefundMybatisIntegrationTest {
    private JdbcTemplate admin, db;
    private String database;
    private CoopInviteMapper mapper;
    private com.chengyinhub.business.mapper.ClubMapper clubs;
    private TransactionTemplate tx;
    private DataSourceTransactionManager manager;
    private CoopDepositRefundService refunds;
    private CoopInviteServiceImpl invites;
    private IWechatPayService wx;
    private AlarmService alarms;
    private WechatVerifiedPaymentFactService paymentFacts;
    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference originalPayment;

    @BeforeAll void database() throws Exception {
        // 2026-09-16: 原来硬编码 127.0.0.1:3317 且自造门控变量 CHENGYIN_DEPOSIT_MYSQL_IT(无任何 CI 注入)
        // ⇒ 27 条从未跑过。统一走 MysqlIntegrationTestConfig + 标准门控变量。
        Map<String,String> env=new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE","cy_deposit_it");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX","_"+UUID.randomUUID().toString().replace("-",""));
        MysqlIntegrationTestConfig mysql=MysqlIntegrationTestConfig.from(env,"it");
        database=mysql.databaseName();
        admin=new JdbcTemplate(new UnpooledDataSource("com.mysql.cj.jdbc.Driver",mysql.adminUrl(),mysql.user(),mysql.password()));
        String version=admin.queryForObject("SELECT VERSION()",String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version),"需要 MySQL >= 8,实际 "+version);
        admin.execute("CREATE DATABASE "+database);
        UnpooledDataSource ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",mysql.databaseUrl(),mysql.user(),mysql.password());db=new JdbcTemplate(ds);
        Path root=Paths.get("").toAbsolutePath();if(!Files.exists(root.resolve("sql/base_schema_20260818.sql")))root=root.getParent();
        String ddl=new String(Files.readAllBytes(root.resolve("sql/base_schema_20260818.sql")),"UTF-8");
        Matcher matcher=Pattern.compile("CREATE TABLE `coop_invite` \\(.*?^\\).*?;",Pattern.DOTALL|Pattern.MULTILINE).matcher(ddl);
        assertTrue(matcher.find());db.execute(matcher.group());
        db.execute("ALTER TABLE coop_invite ADD COLUMN supply_station_id BIGINT NULL"); // New mapper prerequisite, scratch only.
        String migration=new String(Files.readAllBytes(root.resolve("chengyinhub-admin/sql/migration_coop_deposit_refund_state_20260913.sql")),"UTF-8");
        db.execute(migration.substring(migration.indexOf("ALTER TABLE")));
        db.execute("CREATE TABLE deposit_test_topic (id BIGINT PRIMARY KEY, is_settlement INT)");
        db.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY,club_id BIGINT,is_settlement INT)");
        db.execute("CREATE TABLE club (id BIGINT PRIMARY KEY,member_id BIGINT,status INT,del_flag INT)");
        // Existing canonical B schemas/reader, never reconstruct a current intent from a success fact.
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_wx_payment_callback_inbox_outbox_20260725.sql")),
                new org.springframework.core.io.FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261024_wx_verified_payment_fact.sql")),
                new org.springframework.core.io.FileSystemResource(root.resolve("chengyinhub-admin/sql/migration_20261023_wx_pay_order_intent.sql"))).execute(ds);
        paymentFacts=WechatVerifiedFactTestSupport.realFacts(ds);
        Configuration config=new Configuration();config.getTypeAliasRegistry().registerAlias("CoopInvite",CoopInvite.class);
        config.getTypeAliasRegistry().registerAlias("Club",com.chengyinhub.business.domain.Club.class);
        config.setEnvironment(new Environment("local-test",new SpringManagedTransactionFactory(),ds));
        Path xml=root.resolve("chengyinhub-system/src/main/resources/mapper/business/CoopInviteMapper.xml");
        try(InputStream in=Files.newInputStream(xml)){new XMLMapperBuilder(in,config,xml.toString(),config.getSqlFragments()).parse();}
        Path clubXml=root.resolve("chengyinhub-system/src/main/resources/mapper/business/ClubMapper.xml");
        try(InputStream in=Files.newInputStream(clubXml)){new XMLMapperBuilder(in,config,clubXml.toString(),config.getSqlFragments()).parse();}
        SqlSessionTemplate session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        mapper=session.getMapper(CoopInviteMapper.class);clubs=session.getMapper(com.chengyinhub.business.mapper.ClubMapper.class);
        manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
    }
    @AfterAll void cleanup() {
        if(database!=null){admin.execute("DROP DATABASE "+database);assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,database));}
    }
    @BeforeEach void setup() {
        db.execute("DELETE FROM wx_payment_callback_fact_link");
        db.execute("DELETE FROM wx_verified_payment_fact_rejection");
        db.execute("DELETE FROM wx_verified_payment_fact");
        db.execute("DELETE FROM wx_payment_callback_outbox");
        db.execute("DELETE FROM wx_pay_order_intent");
        db.execute("DELETE FROM coop_invite");
        db.execute("DELETE FROM cms_topic");
        db.execute("DELETE FROM club");
        db.execute("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,deposit_status,deposit_amount,deposit_out_trade_no,del_flag,create_time) VALUES(1,0,'talent',9,'merchant',10,3,2,50,'DEP1T100',0,NOW())");
        wx=mock(IWechatPayService.class);alarms=mock(AlarmService.class);refunds=new CoopDepositRefundService();
        WechatPayTestWiring.setField(refunds,"mapper",mapper);WechatPayTestWiring.setField(refunds,"wechatPayService",wx);
        WechatPayTestWiring.setField(refunds,"transactionManager",manager);WechatPayTestWiring.setField(refunds,"alarmService",alarms);
        WechatPayTestWiring.setField(refunds,"verifiedPaymentFacts",paymentFacts);
        db.update("UPDATE coop_invite SET deposit_paid_transaction_id='wx1' WHERE id=1");
        originalPayment=recordPayment("DEP1T100","DEP1T100","wx1","original-merchant",5000);
        invites=new CoopInviteServiceImpl();
        WechatPayTestWiring.setField(invites, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));WechatPayTestWiring.setField(invites,"coopInviteMapper",mapper);
        WechatPayTestWiring.setField(invites,"depositRefundService",refunds);WechatPayTestWiring.setField(invites,"wechatPayService",wx);
        WechatPayTestWiring.setField(invites,"depositPaymentTransactions",manager);
        when(wx.queryRefundFacts("RDEP1T100")).thenReturn(Collections.singletonMap("status","NOT_FOUND"));
        when(wx.submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString())).thenReturn(RefundRequestResult.ACCEPTED);
    }
    private com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference recordPayment(
            String businessNo,String providerNo,String transactionId,String merchant,long totalFen) {
        return paymentFacts.recordVerified(new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,merchant,"original-app",
                businessNo,providerNo,providerNo,transactionId,transactionId,"APP","CNY",totalFen,totalFen,
                java.time.Instant.parse("2026-10-24T00:00:00Z"),null,"SUCCESS"));
    }
    private CoopInvite row(){return mapper.selectCoopInviteById(1L);}
    private void request(){tx.execute(status->{refunds.requestLocked(mapper.selectCoopInviteByIdForUpdate(1L));return null;});}
    private Map<String,String> facts(String state){Map<String,String> f=new HashMap<>();f.put("status",state);f.put("outRefundNo","RDEP1T100");f.put("outTradeNo","DEP1T100");f.put("refund","5000");f.put("total","5000");f.put("currency","CNY");return f;}

    private ICoopInviteService transactionalInvites() {
        org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(invites);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (ICoopInviteService) factory.getProxy();
    }

    @Test void publicRetryApiUsesTransactionalIntentAndRejectsOtherMember() {
        ICoopInviteService service = transactionalInvites();
        com.chengyinhub.web.controller.api.ApiCoopController controller = spy(new com.chengyinhub.web.controller.api.ApiCoopController());
        WechatPayTestWiring.setField(controller, "coopInviteService", service);
        doReturn(88L).when(controller).getAppUserId();
        java.util.Map<String,Object> body = Collections.<String,Object>singletonMap("inviteId", 1L);
        assertNotEquals(200, controller.retryDepositRefund(body).get("code"));
        assertNull(row().getDepositRefundState()); verifyNoInteractions(wx);
        doReturn(9L).when(controller).getAppUserId();
        com.chengyinhub.common.core.domain.AjaxResult result = controller.retryDepositRefund(body);
        assertEquals(200, result.get("code"));
        assertEquals("PROCESSING", ((Map<?,?>)result.get("data")).get("refundState"));
        assertEquals(2, row().getDepositStatus());
        verify(wx).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void completedTopicRequestsOnlyAcceptedCurrentInvitesAndRollsBackAsOneTransaction() {
        db.execute("UPDATE coop_invite SET topic_id=7,status=1 WHERE id=1");
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(7L); topic.setIsSettlement(1);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        WechatPayTestWiring.setField(invites,"cmsTopicMapper",topics);
        ICoopInviteService service = transactionalInvites();
        tx.execute(status -> { service.requestTopicDepositRefunds(7L); status.setRollbackOnly(); return null; });
        assertNull(row().getDepositRefundState()); verifyNoInteractions(wx);
        service.requestTopicDepositRefunds(7L);
        assertEquals("PROCESSING", row().getDepositRefundState());
        assertEquals(2, row().getDepositStatus());
        topic.setIsSettlement(0);
        assertThrows(IllegalStateException.class, () -> service.requestTopicDepositRefunds(7L));
        verify(wx).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void manualAndProcessingRetriesOnlyQueryAndVerifiedSuccessCanResolveManual() {
        request();
        when(wx.queryRefundFacts(anyString())).thenReturn(null);
        refunds.refresh(1L,true); assertEquals("PROCESSING", row().getDepositRefundState());
        when(wx.queryRefundFacts(anyString())).thenReturn(Collections.singletonMap("status","NOT_FOUND"));
        refunds.refresh(1L,true); assertEquals("PROCESSING", row().getDepositRefundState());
        assertTrue(refunds.handle(facts("CLOSED")));
        refunds.refresh(1L,true); assertEquals("MANUAL", row().getDepositRefundState());
        when(wx.queryRefundFacts(anyString())).thenReturn(facts("SUCCESS"));
        transactionalInvites().requestDepositRefund(1L,9L);
        assertEquals("SUCCESS", row().getDepositRefundState()); assertEquals(4,row().getDepositStatus());
        verify(wx).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void preparePaymentCannotUseRepeatableReadSnapshotBeforeCompletedSettlement() throws Exception {
        db.execute("DELETE FROM deposit_test_topic");
        db.execute("INSERT INTO deposit_test_topic VALUES(7,0)");
        db.execute("UPDATE coop_invite SET topic_id=7,status=1,deposit_status=5 WHERE id=1");
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        java.util.function.Function<Boolean,com.chengyinhub.business.domain.CmsTopic> read = locked -> {
            com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
            topic.setId(7L); topic.setLifecycle(3);
            topic.setIsSettlement(db.queryForObject("SELECT is_settlement FROM deposit_test_topic WHERE id=7" + (locked ? " FOR UPDATE" : ""), Integer.class));
            return topic;
        };
        when(topics.selectCmsTopicById(7L)).thenAnswer(call -> read.apply(false));
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(call -> read.apply(true));
        WechatPayTestWiring.setField(invites,"cmsTopicMapper",topics);
        com.chengyinhub.business.mapper.MmsMerchantMapper merchants = mock(com.chengyinhub.business.mapper.MmsMerchantMapper.class);
        com.chengyinhub.business.domain.MmsMerchant merchant = new com.chengyinhub.business.domain.MmsMerchant();
        merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberIdForUpdate(10L)).thenReturn(merchant);
        WechatPayTestWiring.setField(invites,"mmsMerchantMapper",merchants);
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try { tx.execute(status -> {
            assertEquals(0,read.apply(false).getIsSettlement());
            try { writer.submit(() -> db.update("UPDATE deposit_test_topic SET is_settlement=1 WHERE id=7")).get(5,TimeUnit.SECONDS); }
            catch(Exception e){throw new RuntimeException(e);}
            assertEquals(0,read.apply(false).getIsSettlement(), "真实 RR 旧快照仍未结算");
            IllegalStateException failure=assertThrows(IllegalStateException.class, () -> transactionalInvites().prepareDeposit(1L,10L));
            assertTrue(failure.getMessage().contains("已结算"));status.setRollbackOnly();return null;
        }); } finally {writer.shutdownNow();}
        assertEquals(5,row().getDepositStatus());verifyNoInteractions(wx);
    }

    @Test void queryResponseForAnotherExistingRefundCannotSettleEitherRequest() {
        db.execute("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,deposit_status,deposit_amount,deposit_out_trade_no,deposit_refund_no,deposit_refund_state,deposit_refund_amount,del_flag) VALUES(2,0,'talent',9,'merchant',10,3,2,50,'DEP2T100','RDEP2T100','PENDING',50,0)");
        Map<String,String> foreign=facts("SUCCESS"); foreign.put("outRefundNo","RDEP2T100"); foreign.put("outTradeNo","DEP2T100");
        when(wx.queryRefundFacts("RDEP1T100")).thenReturn(foreign);
        request();
        assertEquals(2, mapper.selectCoopInviteById(2L).getDepositStatus());
        assertEquals("PENDING", mapper.selectCoopInviteById(2L).getDepositRefundState());
        assertEquals("MANUAL",row().getDepositRefundState());
        assertEquals(0,invites.settleDepositRefunded(1L,"RDEP1T100"));
        verify(wx,never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void legacyMoneyWriterCannotForgeRefundedButInviteLifecycleRemainsIndependent() {
        assertEquals(0,mapper.updateDeposit(1L,4,null,"RDEP1T100",null));
        assertEquals(2,row().getDepositStatus());
        request();
        CoopInvite patch=new CoopInvite(); patch.setId(1L); patch.setStatus(4);
        assertEquals(1,mapper.updateCoopInvite(patch));
        assertEquals(4,row().getStatus()); assertEquals(2,row().getDepositStatus());
    }

    @Test void paymentQueryMustMatchOriginalTradeCurrencyAmountAndTransaction() {
        db.execute("UPDATE coop_invite SET deposit_status=1 WHERE id=1");
        Map<String,String> fact=new HashMap<>(); fact.put("trade_state","SUCCESS");
        fact.put("out_trade_no","DEP1T100"); fact.put("currency","CNY"); fact.put("total","5000"); fact.put("transaction_id","wx1"); fact.put("wx_out_trade_no","DEP1T100");
        when(wx.lockPaymentAttemptForUpdate("DEP1T100","DEP1T100")).thenReturn(true);
        var verified = mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class);
        var proof = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                "DEP1T100","DEP1T100","DEP1T100","wx1","wx1","JSAPI","CNY",5000,5000,
                java.time.Instant.ofEpochSecond(1700000000),"a".repeat(64),"SUCCESS");
        var reference = new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L,proof.factHash());
        when(verified.requireVerified(reference)).thenReturn(new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,proof));
        when(verified.requireConsumablePayment(reference)).thenReturn(new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,proof));
        when(verified.findPaymentRecovery(reference)).thenReturn(java.util.Optional.empty());
        WechatPayTestWiring.setField(invites,"depositVerifiedFacts",verified);
        fact.put("verified_fact_id","7"); fact.put("verified_fact_hash",reference.hash());
        for (String field:Arrays.asList("out_trade_no","currency","total","transaction_id")) {
            Map<String,String> invalid=new HashMap<>(fact); invalid.put(field,field.equals("total")?"1":field.equals("transaction_id")?"":"OTHER");
            when(wx.queryOrderStatus("DEP1T100")).thenReturn(invalid);
            assertFalse(transactionalInvites().markDepositPaid("DEP1T100")); assertEquals(1,row().getDepositStatus());
        }
        when(wx.queryOrderStatus("DEP1T100")).thenReturn(fact);
        assertTrue(transactionalInvites().markDepositPaid("DEP1T100")); assertEquals(2,row().getDepositStatus());
    }

    @Test void committedRefundIntentDetachesResourcesBeforeChannelQuery() {
        var observed = new java.util.concurrent.atomic.AtomicBoolean();
        when(wx.queryRefundFacts("RDEP1T100")).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("RDEP1T100",row().getDepositRefundNo());
            assertNotNull(row().getDepositRefundState(),"The intent must already be committed/visible before SDK I/O");
            observed.set(true);return Collections.singletonMap("status","NOT_FOUND");
        });
        request();
        assertTrue(observed.get());assertEquals(2,row().getDepositStatus());
        assertEquals("PROCESSING",row().getDepositRefundState());
    }

    @Test void uncommittedRefundCallerCannotDetachItsLocksToQuery() {
        tx.executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class,()->refunds.refresh(1L,true));
            verifyNoInteractions(wx);status.setRollbackOnly();
        });
        assertNull(row().getDepositRefundState());
    }

    @Test void markingDueCannotOverwritePaymentCommittedAfterOldSnapshot() throws Exception {
        db.execute("UPDATE coop_invite SET topic_id=7,status=1,deposit_status=0 WHERE id=1");
        com.chengyinhub.business.mapper.CmsTopicMapper topics=mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic=new com.chengyinhub.business.domain.CmsTopic(); topic.setId(7L);
        when(topics.selectCmsTopicById(7L)).thenReturn(topic);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        WechatPayTestWiring.setField(invites,"cmsTopicMapper",topics);
        WechatPayTestWiring.setField(invites,"notifyService",mock(INotifyService.class));
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try { tx.execute(status -> {
            assertEquals(0,row().getDepositStatus());
            try { writer.submit(() -> db.update("UPDATE coop_invite SET deposit_status=2,deposit_paid_transaction_id='wx1' WHERE id=1")).get(5,TimeUnit.SECONDS); }
            catch(Exception e){throw new RuntimeException(e);}
            transactionalInvites().markTopicDepositsDue(7L);return null;
        }); } finally {writer.shutdownNow();}
        assertEquals(2,row().getDepositStatus());verifyNoInteractions(wx);
    }

    @Test void dueRegistrationPreservesNullInitialStateAndFailsClosedOnZeroAffectedRow() {
        db.execute("UPDATE coop_invite SET topic_id=7,status=1,deposit_status=NULL WHERE id=1");
        com.chengyinhub.business.mapper.CmsTopicMapper topics=mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic=new com.chengyinhub.business.domain.CmsTopic();topic.setId(7L);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(topic);
        WechatPayTestWiring.setField(invites,"cmsTopicMapper",topics);
        INotifyService notice=mock(INotifyService.class);WechatPayTestWiring.setField(invites,"notifyService",notice);
        transactionalInvites().markTopicDepositsDue(7L);
        assertEquals(5,row().getDepositStatus());assertEquals(new BigDecimal("50.00"),row().getDepositAmount());
        clearInvocations(notice);
        db.execute("UPDATE coop_invite SET deposit_status=0,deposit_refund_state='MANUAL' WHERE id=1");
        assertThrows(IllegalStateException.class, () -> transactionalInvites().markTopicDepositsDue(7L));
        assertEquals(0,row().getDepositStatus());verifyNoInteractions(notice,wx);
    }

    @Test void legacyFourBlocksDissolutionUntilActualVerifiedRefundSuccess() {
        db.execute("INSERT INTO cms_topic VALUES(7,73,1)");
        db.execute("UPDATE coop_invite SET topic_id=7,deposit_status=4,deposit_refund_no='RDEP1T100' WHERE id=1");
        assertEquals(1,clubs.selectPendingDepositsByClub(73L).size());
        assertTrue(clubs.selectPendingDepositsByClub(73L).get(0).isRetryable());
        request();assertEquals(1,clubs.selectPendingDepositsByClub(73L).size());
        assertEquals("LEGACY_UNCONFIRMED",row().getDepositRefundState());
        when(wx.queryRefundFacts("RDEP1T100")).thenReturn(facts("SUCCESS"));refunds.refresh(1L,true);
        assertEquals(0,clubs.selectPendingDepositsByClub(73L).size());
        assertEquals(4,row().getDepositStatus());verify(wx,never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        db.execute("UPDATE coop_invite SET deposit_refund_confirmed_at=NULL WHERE id=1");
        assertEquals(1,clubs.selectPendingDepositsByClub(73L).size(),"只有SUCCESS标签而无确认凭据仍阻断");
    }

    private com.chengyinhub.business.mapper.CmsTopicMapper historicalClub() {
        db.execute("INSERT INTO cms_topic VALUES(7,73,1)");db.execute("INSERT INTO club VALUES(73,77,1,0)");
        db.execute("UPDATE coop_invite SET topic_id=7,deposit_status=4,deposit_refund_no='RDEP1T100' WHERE id=1");
        com.chengyinhub.business.mapper.CmsTopicMapper topics=mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(call -> lockedHistoricalTopic());
        WechatPayTestWiring.setField(invites,"cmsTopicMapper",topics);return topics;
    }
    private com.chengyinhub.business.domain.CmsTopic lockedHistoricalTopic() {
        return db.queryForObject("SELECT id,club_id,is_settlement FROM cms_topic WHERE id=7 FOR UPDATE",(rs,n)->{
            com.chengyinhub.business.domain.CmsTopic topic=new com.chengyinhub.business.domain.CmsTopic();
            topic.setId(rs.getLong(1));topic.setClubId(rs.getLong(2));topic.setIsSettlement(rs.getInt(3));return topic;
        });
    }
    @Test void transferredClubOwnerCanOnlyQueryHistoricalFourAndMissingClubFailsClosed() {
        historicalClub();ICoopInviteService service=transactionalInvites();
        assertThrows(IllegalStateException.class,()->service.requestDepositRefund(1L,9L));
        verifyNoInteractions(wx);assertNull(row().getDepositRefundState());
        db.execute("UPDATE coop_invite SET deposit_status=2 WHERE id=1");
        assertThrows(IllegalStateException.class,()->service.requestDepositRefund(1L,77L));verifyNoInteractions(wx);
        db.execute("UPDATE coop_invite SET deposit_status=4 WHERE id=1");
        service.requestDepositRefund(1L,77L);assertEquals("LEGACY_UNCONFIRMED",row().getDepositRefundState());
        verify(wx).queryRefundFacts("RDEP1T100");verify(wx,never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        clearInvocations(wx);db.execute("DELETE FROM club");
        assertThrows(IllegalStateException.class,()->service.requestDepositRefund(1L,77L));verifyNoInteractions(wx);
    }
    @Test void historicalOwnerNowaitAvoidsClubToTopicWaitCycleAndMakesNoChannelCall() throws Exception {
        com.chengyinhub.business.mapper.CmsTopicMapper topics=historicalClub();
        CountDownLatch clubLocked=new CountDownLatch(1),topicLocked=new CountDownLatch(1);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(call->{
            com.chengyinhub.business.domain.CmsTopic topic=lockedHistoricalTopic();topicLocked.countDown();return topic;
        });
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try { Future<?> transfer=worker.submit(()->tx.execute(status->{
            db.update("UPDATE club SET member_id=78 WHERE id=73");clubLocked.countDown();
            try {assertTrue(topicLocked.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            db.update("UPDATE cms_topic SET is_settlement=1 WHERE id=7");return null;
        }));
            assertTrue(clubLocked.await(5,TimeUnit.SECONDS));
            long start=System.nanoTime();
            IllegalStateException failure=assertThrows(IllegalStateException.class,()->transactionalInvites().requestDepositRefund(1L,77L));
            assertTrue(failure.getMessage().contains("身份正在更新"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000,"NOWAIT不能等待治理事务");
            transfer.get(5,TimeUnit.SECONDS);assertNull(row().getDepositRefundState());verifyNoInteractions(wx);
        } finally {topicLocked.countDown();worker.shutdownNow();}
    }

    @Test void reconcileContinuesOtherDepositsThenFailsJobForTechnicalError() {
        assertEquals(1,mapper.beginDepositRefund(1L,"DEP1T100","RDEP1T100",new BigDecimal("50")));
        db.execute("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,status,deposit_status,deposit_amount,deposit_out_trade_no,deposit_refund_no,del_flag) VALUES(2,0,'talent',9,'merchant',10,3,4,50,'DEP2T100','RDEP2T100',0)");
        Map<String,String> second=facts("SUCCESS");second.put("outRefundNo","RDEP2T100");second.put("outTradeNo","DEP2T100");
        when(wx.queryRefundFacts("RDEP2T100")).thenReturn(second);
        CoopDepositRefundService worker=spy(refunds);
        doThrow(new IllegalStateException("storage unavailable for first row")).when(worker).refresh(1L,false);
        assertThrows(IllegalStateException.class,worker::reconcile);
        assertEquals("PENDING",row().getDepositRefundState());
        assertEquals("SUCCESS",mapper.selectCoopInviteById(2L).getDepositRefundState());
        verify(wx,never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void hundredArchivedIntentsCannotStarveLiveRefundOrTriggerArchivedPayouts() {
        db.execute("DELETE FROM coop_invite");db.execute("INSERT INTO cms_topic VALUES(7,73,1)");
        for(int id=1;id<=101;id++) db.update("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,status,deposit_status,deposit_amount,deposit_out_trade_no,deposit_refund_no,deposit_refund_amount,deposit_refund_state,del_flag,create_time) VALUES(?,0,'talent',9,'merchant',10,7,3,2,50,?,?,50,'PENDING',?,DATE_ADD('2026-01-01',INTERVAL ? SECOND))",id,"DEP"+id,"RDEP"+id,id<=100?2:0,id);
        db.update("UPDATE coop_invite SET deposit_paid_transaction_id='wx101' WHERE id=101");
        var livePayment=recordPayment("DEP101","DEP101","wx101","original-merchant",5000);
        when(wx.queryRefundFacts(anyString())).thenReturn(Collections.singletonMap("status","NOT_FOUND"));
        refunds.reconcile();refunds.reconcile();
        assertEquals(100,db.queryForObject("SELECT COUNT(*) FROM coop_invite WHERE del_flag=2 AND deposit_refund_state='MANUAL'",Integer.class));
        assertEquals("PROCESSING",mapper.selectCoopInviteById(101L).getDepositRefundState());
        verify(wx).submitRefund(livePayment,"DEP101","RDEP101",new BigDecimal("50.00"),new BigDecimal("50.00"),"合作保证金退款");
        verify(wx,times(1)).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        assertEquals(101,clubs.selectPendingDepositsByClub(73L).size(),"软删待核验责任不能消失");
        Map<String,String> resolved=facts("SUCCESS");resolved.put("outRefundNo","RDEP1");resolved.put("outTradeNo","DEP1");
        WechatPayServiceImpl support=spy(WechatPayTestWiring.facade());
        WechatPayTestWiring.setField(support,"coopInviteService",invites);
        doReturn(resolved).when(support).queryRefundFacts("RDEP1");
        assertEquals("SUCCESS",support.refreshPayoutFromWx("RDEP1"));
        assertEquals(4,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=1",Integer.class));
        assertEquals(100,clubs.selectPendingDepositsByClub(73L).size(),"已有客服查验入口可凭真实渠道成功解除该笔责任");
        verify(wx,times(1)).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }

    @Test void acceptedRemainsHeldUntilVerifiedChannelSuccess() {
        request();assertEquals(2,row().getDepositStatus());assertEquals("PROCESSING",row().getDepositRefundState());assertNull(row().getDepositRefundConfirmedAt());
        assertTrue(refunds.handle(facts("SUCCESS")));assertEquals(4,row().getDepositStatus());assertEquals("SUCCESS",row().getDepositRefundState());assertNotNull(row().getDepositRefundConfirmedAt());
        assertTrue(refunds.handle(facts("SUCCESS")));assertTrue(refunds.handle(facts("PROCESSING")));assertTrue(refunds.handle(facts("ABNORMAL")));
        assertEquals("SUCCESS",row().getDepositRefundState());verify(wx).submitRefund(originalPayment,"DEP1T100","RDEP1T100",new BigDecimal("50.00"),new BigDecimal("50.00"),"合作保证金退款");
    }
    @Test void callbackBeforeSubmitResponseCannotBeOverwritten() {
        when(wx.submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString())).thenAnswer(call->{assertTrue(refunds.handle(facts("SUCCESS")));return RefundRequestResult.UNKNOWN;});
        request();assertEquals(4,row().getDepositStatus());assertEquals("SUCCESS",row().getDepositRefundState());
    }
    @Test void closedAbnormalAndUnknownNeverBecomeRefunded() {
        when(wx.submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString())).thenReturn(RefundRequestResult.UNKNOWN);
        request();assertEquals("UNKNOWN",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        when(wx.queryRefundFacts(anyString())).thenReturn(null);refunds.refresh(1L,true);verify(wx,times(1)).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        assertTrue(refunds.handle(facts("CLOSED")));assertEquals("MANUAL",row().getDepositRefundState());
        assertTrue(refunds.handle(facts("ABNORMAL")));assertEquals(2,row().getDepositStatus());
    }
    @Test void legacyFourOnlyQueriesNeverDispatchesIncludingMissingChannelRecord() {
        db.execute("UPDATE coop_invite SET deposit_status=4,deposit_refund_no='RDEP1T100' WHERE id=1");
        request();assertEquals(4,row().getDepositStatus());assertEquals("LEGACY_UNCONFIRMED",row().getDepositRefundState());
        verify(wx,never()).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        when(wx.queryRefundFacts(anyString())).thenReturn(facts("SUCCESS"));refunds.refresh(1L,true);
        assertEquals("SUCCESS",row().getDepositRefundState());assertNotNull(row().getDepositRefundConfirmedAt());
    }
    @Test void legacyMissingOrUnrelatedRefundNumberDoesNotDispatchOrConfirm() {
        db.execute("UPDATE coop_invite SET deposit_status=4,deposit_refund_no='UNRELATED' WHERE id=1");
        request();assertEquals("LEGACY_UNCONFIRMED",row().getDepositRefundState());verifyNoInteractions(wx);
        assertFalse(refunds.handle(facts("SUCCESS")));assertNull(row().getDepositRefundConfirmedAt());
    }
    @Test void mismatchedAmountOriginalOrderOrCurrencyCannotConfirm() {
        request();for(String field:Arrays.asList("refund","total","currency","outTradeNo")){
            Map<String,String> f=facts("SUCCESS");f.put(field,field.equals("currency")?"USD":field.equals("outTradeNo")?"DEP_OTHER":"1");assertFalse(refunds.handle(f));
        }assertEquals(2,row().getDepositStatus());assertEquals("PROCESSING",row().getDepositRefundState());
    }
    @Test void rollbackBeforeIntentCommitDoesNotContactChannel() {
        tx.execute(status->{refunds.requestLocked(mapper.selectCoopInviteByIdForUpdate(1L));status.setRollbackOnly();return null;});
        assertNull(row().getDepositRefundState());assertNull(row().getDepositRefundNo());verifyNoInteractions(wx);
    }
    @Test void committedUndispatchedIntentIsRecoveredByExistingReconcile() {
        tx.execute(status->{assertEquals(1,mapper.beginDepositRefund(1L,"DEP1T100","RDEP1T100",new BigDecimal("50")));return null;});
        refunds.reconcile();assertEquals("PROCESSING",row().getDepositRefundState());verify(wx).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
    }
    @Test void expiredLeaseCanRecoverButOldOwnerCannotOverwriteNewState() {
        assertEquals(1,mapper.beginDepositRefund(1L,"DEP1T100","RDEP1T100",new BigDecimal("50")));
        assertEquals(1,mapper.claimDepositRefundDispatch(1L,"old",true));db.execute("UPDATE coop_invite SET deposit_refund_lease_at=DATE_SUB(NOW(),INTERVAL 31 MINUTE) WHERE id=1");
        refunds.reconcile();assertEquals("PROCESSING",row().getDepositRefundState());
        assertEquals(0,mapper.finishDepositRefundDispatch(1L,"old","UNKNOWN"));assertEquals("PROCESSING",row().getDepositRefundState());
    }
    @Test void refundAndDeductionCannotBothOwnTheFrozenMoney() throws Exception {
        CountDownLatch inChannel=new CountDownLatch(1),release=new CountDownLatch(1);
        when(wx.submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString())).thenAnswer(call->{inChannel.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return RefundRequestResult.ACCEPTED;});
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {Future<?> first=workers.submit(this::request);assertTrue(inChannel.await(5,TimeUnit.SECONDS));
            assertEquals(0,invites.deductDeposit(1L));Future<?> duplicate=workers.submit(this::request);duplicate.get(5,TimeUnit.SECONDS);
            release.countDown();first.get(5,TimeUnit.SECONDS);assertEquals(2,row().getDepositStatus());verify(wx).submitRefund(any(com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString());
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void priorDeductionPreventsRefundAndLateCallbackCannotUndoIt() {
        assertEquals(1,invites.deductDeposit(1L));request();verifyNoInteractions(wx);assertFalse(refunds.handle(facts("SUCCESS")));assertEquals(3,row().getDepositStatus());
    }
    @Test void paymentRequiresExactAmountTransactionAndAffectedRow() {
        db.execute("UPDATE coop_invite SET deposit_status=1 WHERE id=1");
        var verified=mock(com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.class);
        var payment=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                "DEP1T100","DEP1T100","DEP1T100","wx1","wx1","JSAPI","CNY",5000,5000,
                java.time.Instant.ofEpochSecond(1700000000),null,"SUCCESS");
        var reference=new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference(7L,payment.factHash());
        when(verified.requireVerified(reference)).thenReturn(new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,payment));
        when(verified.requireConsumablePayment(reference)).thenReturn(new com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService.Stored(reference,payment));
        when(verified.findPaymentRecovery(reference)).thenReturn(java.util.Optional.empty());
        WechatPayTestWiring.setField(invites,"depositVerifiedFacts",verified);
        var consumer=transactionalInvites();
        assertFalse(consumer.markDepositPaid("DEP1T100",1L,"wx1",reference));assertEquals(1,row().getDepositStatus());
        assertTrue(consumer.markDepositPaid("DEP1T100",5000L,"wx1",reference));assertTrue(consumer.markDepositPaid("DEP1T100",5000L,"wx1",reference));
        assertFalse(consumer.markDepositPaid("DEP1T100",5000L,"wx2",reference));assertEquals(2,row().getDepositStatus());
    }
    @Test void unpaidSetoffCasRequiresExactOldStateAndBalanceAndNoRefundIntent() {
        assertEquals(0,mapper.updateDepositIfCurrent(1L,5,new BigDecimal("50"),3,new BigDecimal("50")));
        db.execute("UPDATE coop_invite SET deposit_status=5 WHERE id=1");
        assertEquals(0,mapper.updateDepositIfCurrent(1L,5,new BigDecimal("40"),3,new BigDecimal("40")));
        assertEquals(1,mapper.updateDepositIfCurrent(1L,5,new BigDecimal("50"),5,new BigDecimal("30")));
        assertEquals(0,mapper.updateDepositIfCurrent(1L,5,new BigDecimal("50"),3,new BigDecimal("50")));
    }

    @Test void refundUsesConsumedTransactionEvenWhenANewerSuccessHasAnotherProviderAlias() {
        var later=recordPayment("DEP1T100","WXP-LATER","wx-later","original-merchant",5000);
        assertNotEquals(originalPayment,later);
        request();
        verify(wx).submitRefund(originalPayment,"DEP1T100","RDEP1T100",new BigDecimal("50.00"),
                new BigDecimal("50.00"),"合作保证金退款");
        verify(wx,never()).submitRefund(anyString(),anyString(),any(),any(),anyString());
        assertEquals("wx1",row().getDepositPaidTransactionId());
        assertEquals("PROCESSING",row().getDepositRefundState());
        assertEquals(later,paymentFacts.requireVerified(later).reference());
    }

    @Test void missingConsumedTransactionNeverDispatchesOrGuessesTheOnlyAvailableSuccess() {
        db.update("UPDATE coop_invite SET deposit_paid_transaction_id=NULL WHERE id=1");
        request();
        assertEquals("UNKNOWN",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        assertEquals("RDEP1T100",row().getDepositRefundNo());
        verify(wx,never()).submitRefund(any(),anyString(),anyString(),any(),any(),anyString());
        assertEquals(originalPayment,paymentFacts.requireVerified(originalPayment).reference());
    }

    @Test void unrelatedSuccessCannotReplaceTheConsumedTransactionWithoutItsOwnFact() {
        db.update("UPDATE coop_invite SET deposit_paid_transaction_id='missing-original-tx' WHERE id=1");
        request();
        assertEquals("UNKNOWN",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        verify(wx,never()).submitRefund(any(),anyString(),anyString(),any(),any(),anyString());
        assertEquals(originalPayment,paymentFacts.requireVerified(originalPayment).reference());
    }

    @Test void ambiguousOriginalTransactionAcrossMerchantsNeverSelectsTheLatestOrCurrentMerchant() {
        var conflicting=recordPayment("DEP1T100","DEP1T100","wx1","other-merchant",5000);
        request();
        assertEquals("UNKNOWN",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        verify(wx,never()).submitRefund(any(),anyString(),anyString(),any(),any(),anyString());
        assertEquals(originalPayment,paymentFacts.requireVerified(originalPayment).reference());
        assertEquals(conflicting,paymentFacts.requireVerified(conflicting).reference());
    }

    @Test void frozenRefundAmountDoesNotReplaceTheOriginalPaymentTotal() {
        assertEquals(1,mapper.beginDepositRefund(1L,"DEP1T100","RDEP1T100",new BigDecimal("50")));
        // A pre-existing persisted refund snapshot; this test does not define refund eligibility/allocation.
        db.update("UPDATE coop_invite SET deposit_refund_amount=30 WHERE id=1");
        refunds.refresh(1L,true);
        verify(wx).submitRefund(originalPayment,"DEP1T100","RDEP1T100",new BigDecimal("30.00"),
                new BigDecimal("50.00"),"合作保证金退款");
        Map<String,String> result=facts("SUCCESS");result.put("refund","3000");
        Map<String,String> wrongTotal=new HashMap<>(result);wrongTotal.put("total","3000");
        assertFalse(refunds.handle(wrongTotal));assertEquals(2,row().getDepositStatus());
        assertTrue(refunds.handle(result));assertEquals(4,row().getDepositStatus());
        assertEquals(new BigDecimal("50.00"),row().getDepositAmount());
        assertEquals(new BigDecimal("30.00"),row().getDepositRefundAmount());
    }

    @Test void originalPaidAmountMismatchPreservesTheFrozenRefundAndNeverSubmits() {
        db.update("UPDATE coop_invite SET deposit_amount=49 WHERE id=1");
        request();
        assertEquals("UNKNOWN",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        assertEquals(new BigDecimal("49.00"),row().getDepositRefundAmount());
        verify(wx,never()).submitRefund(any(),anyString(),anyString(),any(),any(),anyString());
        assertEquals(5000,paymentFacts.requireVerified(originalPayment).facts().totalFen());
    }


    @Test void recoveredOriginalEvidenceResumesTheSameFrozenRefundInsteadOfCreatingAnotherIdentity() {
        db.update("UPDATE coop_invite SET deposit_paid_transaction_id='recovered-paid-tx' WHERE id=1");
        request();
        assertEquals("UNKNOWN",row().getDepositRefundState());
        assertEquals("RDEP1T100",row().getDepositRefundNo());
        verify(wx,never()).submitRefund(any(),anyString(),anyString(),any(),any(),anyString());
        var recovered=recordPayment("DEP1T100","WXP-RECOVERED","recovered-paid-tx","original-merchant",5000);
        refunds.refresh(1L,true);
        verify(wx).submitRefund(recovered,"DEP1T100","RDEP1T100",new BigDecimal("50.00"),
                new BigDecimal("50.00"),"合作保证金退款");
        assertEquals("PROCESSING",row().getDepositRefundState());assertEquals(2,row().getDepositStatus());
        assertEquals("RDEP1T100",row().getDepositRefundNo());
        assertEquals(originalPayment,paymentFacts.requireVerified(originalPayment).reference());
    }

}
