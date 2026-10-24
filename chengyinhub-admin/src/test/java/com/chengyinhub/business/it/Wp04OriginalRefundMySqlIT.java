package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** G owner + real MySQL/XML/transactions; topic/member adapters execute real JDBC locks/updates.
 * B's actual producer/reader persists typed fixture facts; no SDK call or provider-verification claim.
 * The terminal CAS and inbox DONE below are explicit G-side SQL fixtures, NOT a test of A's service wiring.
 * No partial-refund, initial allocation, seat counting or negative-margin policy is selected here. */
class Wp04OriginalRefundMySqlIT {
    static final String MIGRATION="migration_20261024_coop_original_refund_components.sql";
    static final String FACT_MIGRATION="migration_20261024_wx_verified_payment_fact.sql";
    static final String HASH="a".repeat(64);
    JdbcTemplate admin, db;
    DriverManagerDataSource source;
    String createdDatabase;
    TransactionTemplate tx;
    CoopSettlementOriginalMapper originals;
    CoopSettlementCashEvidenceMapper cash;
    CoopSettlementOriginalLedger owner;
    com.chengyinhub.business.service.ICoopSettlementClaimService producerClaims;
    WechatVerifiedPaymentFactService facts;
    WechatVerifiedPaymentFact.Reference originalPayment, refundFact;
    AtomicBoolean failAfterWalletReceipt=new AtomicBoolean();

    @BeforeEach void isolatedOriginalSchema() throws Exception {
        assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1",System.getenv("WP04_ORIGINAL_REFUND_DDL_APPROVED"));
        for (String key:List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX"))
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+key),"Explicit isolated connection required: "+key);
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        assertTrue(MybatisItDatabase.port().matches("[0-9]+"));
        int port=Integer.parseInt(MybatisItDatabase.port()); assertTrue(port>0 && port<=65535);
        assertFalse(MybatisItDatabase.user().isBlank());
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
        String database=MybatisItDatabase.databaseName("g_original_"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        assertTrue(database.matches("chengyin_mybatis_g_original_[a-f0-9]{12}[A-Za-z0-9_]*") && database.length()<=64);
        String prefix="jdbc:mysql://"+MybatisItDatabase.host()+":"+port+"/";
        String params="?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin=new JdbcTemplate(new DriverManagerDataSource(prefix+params,MybatisItDatabase.user(),MybatisItDatabase.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `"+database+"`"); createdDatabase=database;
        source=new DriverManagerDataSource(prefix+database+params,MybatisItDatabase.user(),MybatisItDatabase.password());
        db=new JdbcTemplate(source); assertEquals(createdDatabase,db.queryForObject("SELECT DATABASE()",String.class));
        prerequisites(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source)); tx.setTimeout(15);
    }

    static void prerequisites(DriverManagerDataSource source) throws Exception {
        JdbcTemplate db=new JdbcTemplate(source);
        P0AcceptanceSchema.baseTables(db,"ums_member","cms_topic","cms_activity","cms_registration","refund_application",
                "coop_settlement","coop_settlement_adjustment","settlement_claim","coop_settlement_detail",
                "ums_member_balance_detail","wx_payment_callback_inbox");
        P0AcceptanceSchema.migrations(source,"migration_20261023_g_cash_evidence_expand.sql",FACT_MIGRATION,
                "migration_complaint_period_funds_gate_20260917.sql");
    }

    @AfterEach void dropOnlyThisSuccessfullyCreatedDatabase() {
        if (createdDatabase!=null) {
            assertTrue(createdDatabase.matches("chengyin_mybatis_g_original_[a-f0-9]{12}[A-Za-z0-9_]*"));
            admin.execute("DROP DATABASE `"+createdDatabase+"`");
            assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,createdDatabase));
            createdDatabase=null; // DriverManagerDataSource has no pool; all JDBC operations close connections.
        }
    }

    void applyNewSchemaAndFreezeOriginalOrder(boolean paid, String refundApp) throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(paid,refundApp,true);
    }
    void applyNewSchemaAndFreezeOriginalOrder(boolean paid, String refundApp, boolean bindOriginalPaymentReceipt) throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(paid,refundApp,bindOriginalPaymentReceipt,false);
    }
    void applyNewSchemaAndFreezeOriginalOrder(boolean paid, String refundApp, boolean bindOriginalPaymentReceipt, boolean useProductionProducer) throws Exception {
        P0AcceptanceSchema.migrations(source,MIGRATION);
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(source,"CoopSettlementOriginalMapper","CoopSettlementCashEvidenceMapper",
                "CoopSettlementMapper","CoopSettlementDetailMapper","CoopSettlementAdjustmentMapper","WechatVerifiedPaymentFactMapper",
                "UmsMemberSettlementDebtMapper","UmsMemberBalanceDetailMapper");
        originals=sql.getMapper(CoopSettlementOriginalMapper.class); cash=sql.getMapper(CoopSettlementCashEvidenceMapper.class);
        facts=new WechatVerifiedPaymentFactService(); ReflectionTestUtils.setField(facts,"mapper",sql.getMapper(WechatVerifiedPaymentFactMapper.class));
        // Committed via B's real storage owner before the business transaction, never by a G VERIFIED table/flag.
        originalPayment=facts.recordVerified(payment());
        if (!bindOriginalPaymentReceipt) facts.recordVerified(refund(refundApp));
        db.update("INSERT INTO ums_member(id,balance) VALUES(7,2.00),(77,0.00)");
        db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant) VALUES(11,'G original refund fixture',1,7,1,0.10,0,0,0)");
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time,transaction_id,wechat_payment_amount) VALUES(1,'REG1',1,11,21,10,77,1,10,10,10,2,2,0,1,'2026-10-01 08:00:00','WXTX1',10)");
        db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,status,amount,del_flag) VALUES(11,11,NULL,'ACCOUNTING','PLATFORM','platform',1,1,0),(12,11,7,'MEMBER','INITIATOR_MARGIN','initiator',?,9,0)",paid?1:0);
        if (paid) {
            db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,0,9,9,'Coop结算打款 - 主题11',5,12,1,0,NOW())");
            // Controlled historical spend fixture explains the remaining 2.00; it does not test a spend service.
            db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,9,2,-7,'IT subsequent balance use',9001,1,2,0,NOW())");
        }
        String paymentHash=WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:WXTX1:SUCCESS");
        db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(900,UNHEX(?),1,1,'SUCCESS',2)",paymentHash);
        if (bindOriginalPaymentReceipt) facts.bindNewInbox(900,paymentHash,originalPayment,true);
        CmsTopicMapper topics=mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call -> db.queryForObject("SELECT id,is_settlement,member_id,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant FROM cms_topic WHERE id=11 FOR UPDATE",(rs,n)->{
            CmsTopic row=new CmsTopic(); row.setId(rs.getLong(1)); row.setIsSettlement(rs.getInt(2));row.setMemberId(rs.getLong(3));
            row.setFeePlatformRate(rs.getBigDecimal(4));row.setFeeGuaranteeBaseRate(rs.getBigDecimal(5));
            row.setFeeGuaranteeStepRate(rs.getBigDecimal(6));row.setFeeGuaranteeMaxMerchant(rs.getInt(7));return row;
        }));
        UmsMemberMapper members=mock(UmsMemberMapper.class);
        when(members.selectBalanceForUpdate(7L)).thenAnswer(call -> db.queryForObject("SELECT balance FROM ums_member WHERE id=7 FOR UPDATE",BigDecimal.class));
        when(members.deductBalance(eq(7L),any())).thenAnswer(call -> {
            BigDecimal amount=call.getArgument(1);
            return db.update("UPDATE ums_member SET balance=balance-? WHERE id=7 AND balance>=?",amount,amount);
        });
        UmsMemberBalanceDetailMapper flows=sql.getMapper(UmsMemberBalanceDetailMapper.class);
        IUmsMemberBalanceDetailService ledger=mock(IUmsMemberBalanceDetailService.class);
        when(ledger.insertUmsMemberBalanceDetail(any())).thenAnswer(call -> {
            int count=flows.insertUmsMemberBalanceDetail(call.getArgument(0));
            if (failAfterWalletReceipt.get()) throw new IllegalStateException("INJECTED_AFTER_REAL_WALLET_RECEIPT");
            return count;
        });
        SettlementDebtLedger debts=new SettlementDebtLedger(sql.getMapper(UmsMemberSettlementDebtMapper.class),members,flows);
        owner=new CoopSettlementOriginalLedger(topics,cash,originals,sql.getMapper(CoopSettlementMapper.class),
                sql.getMapper(CoopSettlementAdjustmentMapper.class),facts,members,ledger,debts);
        CoopSettlementCashClose close=new CoopSettlementCashClose(); close.setTopicId(11L); close.setCommercialContextType("TOPIC_EDITION");
        close.setCommercialContextId(11L); close.setCloseKind("POSITIVE_SALES"); close.setInitiatorMemberId(7L);
        close.setOriginalSalesBasis(money("10")); close.setOriginalContributionCount(1); close.setPlatformRate(new BigDecimal("0.10"));
        close.setGuaranteeBaseRate(BigDecimal.ZERO); close.setGuaranteeStepRate(BigDecimal.ZERO); close.setGuaranteeMaxMerchant(0);
        close.setOriginalVerifiedMerchantCount(0); close.setOriginCalculationVersion("EXACT_SINGLE_ORDER_V1");
        close.setContractSnapshot("{\"fixture\":\"one-order-no-allocation-rounding\"}"); close.setSourceFingerprint(HASH); close.setClosedAt(new Date(1700000000123L));
        CoopSettlementCashContribution contribution=new CoopSettlementCashContribution(); contribution.setRegistrationId(1L); contribution.setPayerRegistrationId(1L);
        contribution.setRegistrationNo("REG1"); contribution.setPayerOrderNo("REG1"); contribution.setOwnerType(1); contribution.setOwnerId(11L); contribution.setSourceTopicId(11L);
        contribution.setSnapshotPaymentStatus(2); contribution.setSnapshotRegistrationStatus(2); contribution.setSnapshotVerificationStatus(0);
        contribution.setSnapshotPayableAmount(money("10")); contribution.setSalesBasisAmount(money("10"));
        contribution.setRegistrationSnapshot("{\"wechatPaymentAmount\":10.00}"); contribution.setSourceFingerprint(HASH);
        if (useProductionProducer) {
            CoopSettlementClaimServiceImpl claimOwner=new CoopSettlementClaimServiceImpl(null,null,null,null,
                    sql.getMapper(CoopSettlementMapper.class),sql.getMapper(CoopSettlementDetailMapper.class),sql.getMapper(CoopSettlementAdjustmentMapper.class));
            ReflectionTestUtils.setField(claimOwner,"topicMapper",topics);
            ReflectionTestUtils.setField(claimOwner,"originalLedger",owner);
            ReflectionTestUtils.setField(claimOwner,"originalProducer",new CoopSettlementOriginalProducer(topics,originals,cash,owner));
            producerClaims=claimOwner;
            CmsTopic originalTopic=new CmsTopic();originalTopic.setId(11L);originalTopic.setMemberId(7L);originalTopic.setFeePlatformRate(new BigDecimal("0.10"));
            originalTopic.setFeeGuaranteeBaseRate(BigDecimal.ZERO);originalTopic.setFeeGuaranteeStepRate(BigDecimal.ZERO);originalTopic.setFeeGuaranteeMaxMerchant(0);
            tx.executeWithoutResult(status -> {
                var basis=com.chengyinhub.business.domain.dto.TopicSettlementCashBasis.fromLockedRows(11L,originals.selectOriginalRegistrationsForUpdate(11L));
                assertEquals("FROZEN_EXACT_SINGLE_ORDER",producerClaims.freezeSingleOrderOriginals(originalTopic,basis,new Date(1700000000123L)));
            });
        } else {
            tx.executeWithoutResult(status -> owner.freezeOriginals(close,List.of(contribution),List.of(component(11L,null,"ACCOUNTING","PLATFORM","1"),component(12L,7L,"MEMBER","INITIATOR_MARGIN","9"))));
        }
        assertEquals(2,count("coop_settlement_original_component"));
        db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(901,'RF901','registration',1,1,11,1,0,10,10,0)");
        refundFact=facts.recordVerified(refund(refundApp));
        String notifyHash=WxPaymentCallbackInboxServiceImpl.sha256Hex("REFUND:RF901:SUCCESS");
        db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(901,UNHEX(?),2,901,'SUCCESS',0)",notifyHash);
        facts.bindNewInbox(901,notifyHash,refundFact,true);

    }

    void terminalSuccessAndHook() {
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            db.update("UPDATE refund_application SET payout_status=1 WHERE id=901 AND payout_status=0");
            if (producerClaims==null) owner.reverseCompletedRegistrationRefund(901L);
            else producerClaims.reverseCompletedRegistrationRefund(901L);
            db.update("UPDATE wx_payment_callback_inbox SET state=2 WHERE id=901");
        });
    }

    @Test void realClaimProducerBridgeFreezesOriginalsThenSameRefundHookConsumesThoseExactRows() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",true,true);
        assertEquals("CLASSIC_SINGLE_ORDER_ORIGIN_V1",db.queryForObject("SELECT origin_calculation_version FROM coop_settlement_cash_close",String.class));
        terminalSuccessAndHook();terminalSuccessAndHook();
        assertEquals(1,count("coop_settlement_refund_receipt"));assertEquals(2,count("coop_settlement_original_adjustment"));
        assertMoney("-10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        assertMoney("10",db.queryForObject("SELECT SUM(original_amount) FROM coop_settlement_original_component",BigDecimal.class));
        assertEquals(2,count("wx_verified_payment_fact"));assertMoney("2",balance());
    }

    @Test void actualBinaryInboxAndHexSidecarYieldExactBReferenceAndFullRefundOnce() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        db.update("UPDATE refund_application SET payout_status=1 WHERE id=901");
        assertEquals(refundFact,tx.execute(status -> originals.selectRefundVerifiedReferenceForUpdate(901L)));
        assertEquals(32,db.queryForObject("SELECT OCTET_LENGTH(notify_hash) FROM wx_payment_callback_inbox WHERE id=901",Integer.class));
        assertEquals(64,db.queryForObject("SELECT OCTET_LENGTH(notify_hash) FROM wx_payment_callback_fact_link WHERE inbox_id=901",Integer.class));
        terminalSuccessAndHook(); terminalSuccessAndHook();
        assertEquals(1,count("coop_settlement_refund_receipt")); assertEquals(2,count("coop_settlement_original_adjustment"));
        assertMoney("-10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        assertMoney("10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement",BigDecimal.class));
        assertMoney("2",balance()); assertEquals(0,count("ums_member_settlement_debt"));
        assertEquals(0,db.queryForObject("SELECT status FROM coop_settlement WHERE id=12",Integer.class));
    }

    @Test void unboundPaymentFactCannotFreezeOriginalCashAndFailureRollsBackWholePlan() {
        assertEquals("ORIGINAL_COMPONENT_WRITE_CONFLICT",assertThrows(IllegalStateException.class,
                ()->applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",false)).getMessage());
        assertEquals(0,count("coop_settlement_cash_close")); assertEquals(0,count("coop_settlement_cash_contribution"));
        assertEquals(0,count("coop_settlement_original_component")); assertEquals(2,count("wx_verified_payment_fact"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE cash_close_id IS NOT NULL",Integer.class));
    }

    @Test void paidPrincipalRecoversBalanceAndDebtUsingOriginalAdjustmentIdentity() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(true,"wx-original"); terminalSuccessAndHook(); terminalSuccessAndHook();
        assertMoney("0",balance()); assertMoney("7",db.queryForObject("SELECT amount FROM ums_member_settlement_debt",BigDecimal.class));
        assertEquals("coop_refund_component",db.queryForObject("SELECT source_type FROM ums_member_settlement_debt",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ums_member_settlement_debt d JOIN coop_settlement_original_adjustment a ON a.id=d.source_id WHERE a.settlement_id=12 AND a.wallet_debit=2 AND a.debt_amount=7",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94 AND change_balance=-2",Integer.class));
        assertEquals(2,count("wx_verified_payment_fact"));
    }

    @Test void failureAfterActualWalletWriteRollsBackTerminalReceiptEffectsAndInboxButPreservesBFact() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(true,"wx-original"); failAfterWalletReceipt.set(true);
        assertThrows(IllegalStateException.class,this::terminalSuccessAndHook);
        assertMoney("2",balance()); assertEquals(0,count("coop_settlement_refund_receipt"));
        assertEquals(0,count("coop_settlement_original_adjustment")); assertEquals(0,count("ums_member_settlement_debt"));
        assertEquals(0,db.queryForObject("SELECT payout_status FROM refund_application WHERE id=901",Integer.class));
        assertEquals(0,db.queryForObject("SELECT state FROM wx_payment_callback_inbox WHERE id=901",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94",Integer.class));
        assertEquals(refundFact,facts.requireVerified(refundFact).reference());
        failAfterWalletReceipt.set(false); terminalSuccessAndHook(); assertEquals(1,count("coop_settlement_refund_receipt"));
    }

    @Test void differentOriginalAppIsRejectedWithoutCommittingLocalSuccessOrMoney() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(true,"wx-other");
        assertEquals("VERIFIED_REFUND_ORIGINAL_IDENTITY_CONFLICT",assertThrows(IllegalStateException.class,this::terminalSuccessAndHook).getMessage());
        assertMoney("2",balance()); assertEquals(0,count("coop_settlement_refund_receipt")); assertEquals(0,count("coop_settlement_original_adjustment"));
        assertEquals(0,count("ums_member_settlement_debt")); assertEquals(2,count("wx_verified_payment_fact"));
        assertEquals(0,db.queryForObject("SELECT payout_status FROM refund_application WHERE id=901",Integer.class));
    }

    @Test void concurrentSameSuccessFactCreatesExactlyOneCompleteEffect() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(true,"wx-original");
        ExecutorService workers=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Void> action=()->{assertTrue(start.await(5,TimeUnit.SECONDS));terminalSuccessAndHook();return null;};
            Future<Void> one=workers.submit(action),two=workers.submit(action); start.countDown();
            one.get(20,TimeUnit.SECONDS); two.get(20,TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS)); }
        assertEquals(1,count("coop_settlement_refund_receipt")); assertEquals(2,count("coop_settlement_original_adjustment"));
        assertEquals(1,count("ums_member_settlement_debt")); assertMoney("0",balance());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94",Integer.class));
    }

    @Test void compositeAdjustmentFkRejectsBorrowedExistingIdsFromAnotherClose() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original"); terminalSuccessAndHook();
        db.update("INSERT INTO cms_topic(id,name,product_type,is_settlement) VALUES(22,'G zero anchor with unknown owner',1,1)");
        db.update("INSERT INTO coop_settlement_cash_close(id,topic_id,commercial_context_type,commercial_context_id,close_kind,original_sales_basis,original_contribution_count,original_verified_merchant_count,origin_calculation_version,contract_snapshot,source_fingerprint,closed_at) VALUES(22,22,'TOPIC_EDITION',22,'ZERO_SALES',0,0,0,'ZERO_ANCHOR',JSON_OBJECT(),?,NOW())",HASH);
        // Each component/receipt/head ID exists; only the tuple's close differs. No NULL bypass.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update(
                "UPDATE coop_settlement_original_adjustment SET cash_close_id=22 WHERE settlement_id=11"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement_original_adjustment WHERE cash_close_id=22",Integer.class));
        assertEquals(2,count("coop_settlement_original_adjustment"));
    }

    @Test void reservedConstraintNameIsRejectedBeforeAnyNewTableDdl() {
        db.execute("ALTER TABLE ums_member ADD CONSTRAINT ck_goa_payload CHECK (balance>=0)");
        assertThrows(org.springframework.dao.DataAccessException.class,()->P0AcceptanceSchema.migrations(source,MIGRATION));
        assertEquals(0,newTableCount(db));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='ums_member' AND constraint_name='ck_goa_payload'",Integer.class));
    }

    @Test void eachInterruptedEmptyCreatePrefixCanBeShapeCheckedAndRemovedThenApplied() throws Exception {
        List<String> forward=forward();
        for (int stage=1;stage<=3;stage++) {
            for (int i=0;i<=stage;i++) db.execute(forward.get(i));
            assertEquals(stage,newTableCount(db));
            assertEquals(stage,recoverEmptyPrefix()); assertEquals(0,newTableCount(db));
        }
        P0AcceptanceSchema.migrations(source,MIGRATION); assertEquals(3,newTableCount(db));
    }

    @Test void recoveryRejectsChangedShapeAndNonemptyOriginalEvidenceWithoutDroppingAnything() throws Exception {
        P0AcceptanceSchema.migrations(source,MIGRATION);
        db.execute("ALTER TABLE coop_settlement_original_adjustment ADD unexpected_payload INT NULL");
        assertEquals("ORIGINAL_RECOVERY_SHAPE_CONFLICT",assertThrows(IllegalStateException.class,this::recoverEmptyPrefix).getMessage());
        assertEquals(3,newTableCount(db));
        db.execute("ALTER TABLE coop_settlement_original_adjustment DROP COLUMN unexpected_payload");
        assertEquals(3,recoverEmptyPrefix());
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        assertEquals("ORIGINAL_RECOVERY_NONEMPTY",assertThrows(IllegalStateException.class,this::recoverEmptyPrefix).getMessage());
        assertEquals(3,newTableCount(db)); assertEquals(2,count("coop_settlement_original_component"));
    }

    private static final String[] NEW={"coop_settlement_original_component","coop_settlement_refund_receipt","coop_settlement_original_adjustment"};
    private static final String NAMES="('coop_settlement_original_component','coop_settlement_refund_receipt','coop_settlement_original_adjustment')";

    /** CI UUID scratch only. No production entry. Writers must be stopped; no DELETE, old ALTER or wildcard DROP.
     * Canonical metadata comes from this same MySQL server and the exact forward source, not a guessed digest.
     * Every inverse returns to an accepted CREATE prefix, so an interrupted cleanup can be restarted safely. */
    int recoverEmptyPrefix() throws Exception {
        assertNotNull(createdDatabase); assertEquals(createdDatabase,db.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_original_[a-f0-9]{12}[A-Za-z0-9_]*"));
        String reference=MybatisItDatabase.databaseName("g_original_ref_"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        assertTrue(reference.matches("chengyin_mybatis_g_original_ref_[a-f0-9]{12}[A-Za-z0-9_]*") && reference.length()<=64);
        boolean created=false;
        try {
            admin.execute("CREATE DATABASE `"+reference+"`"); created=true;
            String marker="/"+createdDatabase+"?"; assertTrue(source.getUrl().contains(marker));
            DriverManagerDataSource canonical=new DriverManagerDataSource(source.getUrl().replace(marker,"/"+reference+"?"),source.getUsername(),source.getPassword());
            prerequisites(canonical); JdbcTemplate referenceDb=new JdbcTemplate(canonical);
            List<String> forward=forward(); List<List<List<Map<String,Object>>>> expected=new ArrayList<>();
            referenceDb.execute(forward.get(0)); expected.add(shape(referenceDb));
            for (int i=1;i<=3;i++) {referenceDb.execute(forward.get(i));expected.add(shape(referenceDb));}
            var actual=shape(db); int stage=-1;
            for (int i=0;i<expected.size();i++) if (expected.get(i).equals(actual)) stage=i;
            if (stage<0) throw new IllegalStateException("ORIGINAL_RECOVERY_SHAPE_CONFLICT");
            for (int i=0;i<stage;i++) if (count(NEW[i])!=0) throw new IllegalStateException("ORIGINAL_RECOVERY_NONEMPTY");
            int originalStage=stage;
            while (stage>0) {
                db.execute("DROP TABLE "+NEW[stage-1]); stage--;
                if (!shape(db).equals(expected.get(stage))) throw new IllegalStateException("ORIGINAL_RECOVERY_INVERSE_SHAPE_CONFLICT");
            }
            return originalStage;
        } finally {
            if (created) {
                admin.execute("DROP DATABASE `"+reference+"`");
                assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,reference));
            }
        }
    }

    static List<String> forward() throws Exception {
        // This candidate has no DELIMITER, inline comments or semicolons inside literals; reject any future layout change.
        String sql=String.join("\n",Files.readAllLines(P0AcceptanceSchema.repoRoot().resolve("chengyinhub-admin/sql").resolve(MIGRATION))
                .stream().filter(line->!line.stripLeading().startsWith("--")).toList());
        List<String> statements=Arrays.stream(sql.split(";")).map(String::trim).filter(s->!s.isEmpty()).toList();
        assertEquals(4,statements.size()); assertTrue(statements.get(0).startsWith("SELECT CAST(IF("));
        for (int i=0;i<3;i++) assertTrue(statements.get(i+1).startsWith("CREATE TABLE "+NEW[i]+" ("));
        return statements;
    }
    static int newTableCount(JdbcTemplate db) {return db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN "+NAMES,Integer.class);}
    static List<List<Map<String,Object>>> shape(JdbcTemplate db) {
        List<List<Map<String,Object>>> shape=new ArrayList<>();
        for (String query:List.of(
                "SELECT table_name,table_type,engine,row_format,table_collation,create_options,table_comment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN "+NAMES+" ORDER BY table_name",
                "SELECT table_name,column_name,ordinal_position,column_type,is_nullable,column_default,extra,generation_expression,character_set_name,collation_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN "+NAMES+" ORDER BY table_name,ordinal_position",
                "SELECT table_name,index_name,seq_in_index,column_name,non_unique,sub_part,index_type,collation,is_visible,expression FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN "+NAMES+" ORDER BY table_name,index_name,seq_in_index",
                "SELECT table_name,constraint_name,constraint_type,enforced FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name IN "+NAMES+" ORDER BY table_name,constraint_name",
                "SELECT CASE WHEN constraint_schema=DATABASE() THEN 'LOCAL' ELSE constraint_schema END AS owner_scope,table_name,constraint_name,column_name,ordinal_position,referenced_table_name,referenced_column_name,CASE WHEN referenced_table_schema IS NULL THEN NULL WHEN referenced_table_schema=DATABASE() THEN 'LOCAL' ELSE referenced_table_schema END AS referenced_scope FROM information_schema.key_column_usage WHERE (constraint_schema=DATABASE() AND (table_name IN "+NAMES+" OR referenced_table_name IN "+NAMES+")) OR (referenced_table_schema=DATABASE() AND referenced_table_name IN "+NAMES+") ORDER BY owner_scope,table_name,constraint_name,ordinal_position",
                "SELECT table_name,constraint_name,match_option,update_rule,delete_rule FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name IN "+NAMES+" ORDER BY table_name,constraint_name",
                "SELECT t.table_name,t.constraint_name,c.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints c ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE t.constraint_schema=DATABASE() AND t.table_name IN "+NAMES+" ORDER BY t.table_name,t.constraint_name",
                "SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY trigger_name")) shape.add(db.queryForList(query));
        return shape;
    }

    CoopSettlementOriginalComponent component(Long head,Long receiver,String kind,String line,String amount) {
        CoopSettlementOriginalComponent row=new CoopSettlementOriginalComponent();row.setSourceRegistrationId(1L);row.setPayerRegistrationId(1L);
        row.setSettlementId(head);row.setReceiverKind(kind);row.setReceiverMemberId(receiver);row.setLineType(line);
        row.setOriginalAmount(money(amount));row.setOriginalOrderCash(money("10"));row.setCalculationVersion("EXACT_SINGLE_ORDER_V1");
        row.setPaymentFactId(originalPayment.id());row.setPaymentFactHash(originalPayment.hash());return row;
    }
    static WechatVerifiedPaymentFact payment() {return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original","REG1","WXP1","WXP1","WXTX1","WXTX1","JSAPI","CNY",1000,1000,Instant.ofEpochSecond(1),null,"SUCCESS");}
    static WechatVerifiedPaymentFact refund(String app) {return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant",app,"REG1","WXP1","RF901","WXREF1","WXTX1","JSAPI","CNY",1000,1000,Instant.ofEpochSecond(2),null,"REFUND");}
    int count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    BigDecimal balance() {return db.queryForObject("SELECT balance FROM ums_member WHERE id=7",BigDecimal.class);}
    static BigDecimal money(String value) {return new BigDecimal(value).setScale(2);}
    static void assertMoney(String expected,BigDecimal actual) {assertEquals(0,money(expected).compareTo(actual));}
}
