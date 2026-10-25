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
 * Partial reversals use the approved cumulative/bounded cent policy; initial allocation, seat counting
 * and negative-margin policy remain outside this fixture. */
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
    WechatVerifiedPaymentFactService facts;
    WechatVerifiedPaymentFact.Reference originalPayment, refundFact;
    AtomicBoolean failAfterWalletReceipt=new AtomicBoolean();
    private DepositSetoffStage depositStage;

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
        P0AcceptanceSchema.migrations(source,"migration_20261023_wx_pay_order_intent.sql","migration_20261023_g_cash_evidence_expand.sql",FACT_MIGRATION,
                "migration_complaint_period_funds_gate_20260917.sql", "migration_20261024_registration_payment_fact.sql");
    }

    /** Full current station dependency shape, shared by the original-order fixtures.
     * Execute once on the owned fresh schema; never emulate just the newly selected column. */
    static void stationScopeSchema(DriverManagerDataSource source) throws Exception {
        JdbcTemplate db = new JdbcTemplate(source);
        P0AcceptanceSchema.baseTables(db, "cms_registration_merchant");
        P0AcceptanceSchema.migrations(source, "migration_business_audit_event_20260823.sql");
        String governance = Files.readString(P0AcceptanceSchema.repoRoot().resolve(
                "chengyinhub-admin/sql/migration_approval_governance_20260828.sql"),
                java.nio.charset.StandardCharsets.UTF_8);
        var alter = java.util.regex.Pattern.compile("(?s)ALTER TABLE business_audit_event\\s+.*?;").matcher(governance);
        assertTrue(alter.find()); db.execute(alter.group()); assertFalse(alter.find());
        P0AcceptanceSchema.migrations(source, "migration_20261025_coop_order_station_fulfillment.sql");
        assertEquals("YES", db.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='coop_invite' AND column_name='supply_station_id'", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM coop_invite WHERE supply_station_id IS NOT NULL", Integer.class));
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
    void applyNewSchemaAndFreezeOriginalOrder(boolean paid, String refundApp, boolean bindOriginalPaymentReceipt,
            boolean bindConsumedPaymentReceipt) throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(paid,refundApp,bindOriginalPaymentReceipt,bindConsumedPaymentReceipt,money("10"));
    }
    void applyNewSchemaAndFreezeOriginalOrder(boolean paid, String refundApp, boolean bindOriginalPaymentReceipt,
            boolean bindConsumedPaymentReceipt, BigDecimal initialRefundAmount) throws Exception {
        P0AcceptanceSchema.migrations(source,MIGRATION);
        if (depositStage != null) {
            P0AcceptanceSchema.baseTables(db,"coop_invite","coop_order","coop_complaint");
            P0AcceptanceSchema.migrations(source,"migration_coop_deposit_refund_state_20260913.sql");
            stationScopeSchema(source);
        }
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(source,"CmsRegistrationPaymentFactMapper","CoopSettlementOriginalMapper","CoopSettlementCashEvidenceMapper","CoopSettlementEvidenceMapper",

                "CoopSettlementMapper","CoopSettlementAdjustmentMapper","WechatVerifiedPaymentFactMapper",
                "UmsMemberSettlementDebtMapper","UmsMemberBalanceDetailMapper","SettlementClaimMapper","CoopSettlementDetailMapper","CoopInviteMapper","CoopComplaintMapper");
        originals=sql.getMapper(CoopSettlementOriginalMapper.class); cash=sql.getMapper(CoopSettlementCashEvidenceMapper.class);
        facts=WechatVerifiedFactTestSupport.realFacts(source);
        db.update("INSERT INTO ums_member(id,balance) VALUES(7,2.00),(77,0.00)");
        db.update("INSERT INTO cms_topic(id,name,product_type,member_id,is_settlement,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant) VALUES(11,'G original refund fixture',1,7,1,0.10,0,0,0)");
        db.update("INSERT INTO cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time,transaction_id,wechat_payment_amount) VALUES(1,'REG1',1,11,21,10,77,1,10,10,10,1,1,0,1,'2026-10-01 08:00:00',NULL,10)");
        // The original payable row exists before provider success; only then arrange the original paid fixture.
        originalPayment=facts.recordVerified(payment());
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=2,transaction_id='WXTX1' WHERE id=1");
        refundFact=facts.recordVerified(refund(refundApp,"RF901","WXREF1",initialRefundAmount));
        if (depositStage == null) {
            db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,status,amount,del_flag) VALUES(11,11,NULL,'ACCOUNTING','PLATFORM','platform',1,1,0),(12,11,7,'MEMBER','INITIATOR_MARGIN','initiator',?,9,0)",paid?1:0);
        } else {
            db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,status,amount,del_flag) VALUES(11,11,NULL,'ACCOUNTING','PLATFORM','platform',1,1,0)");
        }
        if (paid) {
            db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,0,9,9,'Coop结算打款 - 主题11',5,12,1,0,NOW())");
            // Controlled historical spend fixture explains the remaining 2.00; it does not test a spend service.
            db.update("INSERT INTO ums_member_balance_detail(member_id,current_balance,after_balance,change_balance,change_reason,event_type,event_id,change_type,del_flag,create_time) VALUES(7,9,2,-7,'IT subsequent balance use',9001,1,2,0,NOW())");
        }
        String paymentHash=WxPaymentCallbackInboxServiceImpl.sha256Hex("PAY:WXTX1:SUCCESS");
        if (!bindConsumedPaymentReceipt)
            db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(900,UNHEX(?),1,1,'SUCCESS',2)",paymentHash);
        if (bindOriginalPaymentReceipt) facts.bindNewInbox(900,paymentHash,originalPayment,true);
        String notifyHash=WxPaymentCallbackInboxServiceImpl.sha256Hex("REFUND:RF901:SUCCESS");
        db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(901,UNHEX(?),2,901,'SUCCESS',0)",notifyHash);
        facts.bindNewInbox(901,notifyHash,refundFact,true);
        CmsTopicMapper topics=mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(11L)).thenAnswer(call -> db.queryForObject("SELECT id,is_settlement FROM cms_topic WHERE id=11 FOR UPDATE",(rs,n)->{
            CmsTopic row=new CmsTopic(); row.setId(rs.getLong(1)); row.setIsSettlement(rs.getInt(2)); return row;
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
        var consumedPayments = sql.getMapper(CmsRegistrationPaymentFactMapper.class);
        var consumedReader = new com.chengyinhub.business.service.support.RegistrationPaymentConsumptionReadService(consumedPayments);
        owner=new CoopSettlementOriginalLedger(topics,cash,originals,sql.getMapper(CoopSettlementMapper.class),
                sql.getMapper(CoopSettlementAdjustmentMapper.class),facts,consumedReader,members,ledger,debts);
        ReflectionTestUtils.setField(owner,"originalCloseEvidence",sql.getMapper(CoopSettlementEvidenceMapper.class));

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
        if (depositStage != null) {
            when(members.lockMemberById(7L)).thenAnswer(call -> db.queryForObject("SELECT id FROM ums_member WHERE id=7 FOR UPDATE",Long.class));
            when(members.selectUmsMemberById(7L)).thenAnswer(call -> db.queryForObject("SELECT id,balance FROM ums_member WHERE id=7",(rs,n) -> {
                UmsMember member=new UmsMember();member.setId(rs.getLong(1));member.setBalance(rs.getBigDecimal(2));return member;
            }));
            when(members.addBalance(eq(7L),any())).thenAnswer(call -> {
                BigDecimal amount=call.getArgument(1);return db.update("UPDATE ums_member SET balance=balance+? WHERE id=7",amount);
            });
            depositStage.initialize(sql,topics,members,ledger,debts);
        }
        tx.executeWithoutResult(status -> {
            if (bindConsumedPaymentReceipt) assertEquals(1,consumedPayments.insertConsumed(1L,originalPayment.id(),originalPayment.hash()));
            if (depositStage == null) {
                owner.freezeOriginals(close,List.of(contribution),List.of(component(11L,null,"ACCOUNTING","PLATFORM","1"),component(12L,7L,"MEMBER","INITIATOR_MARGIN","9")));
            } else {
                depositStage.persistOriginal();
                List<CoopSettlement> credits=depositStage.deferred?List.of():depositStage.apply();
                List<CoopSettlementOriginalComponent> components=new ArrayList<>();
                components.add(component(11L,null,"ACCOUNTING","PLATFORM","1"));
                for(var head:depositStage.sourceHeads) {
                    var merchant=component(head.getId(),7L,"MEMBER","MERCHANT_VERIFY",head.getAmount().toPlainString());
                    merchant.setSourceClaimId(depositStage.sourceDetails.get(head.getId()).getClaimId());components.add(merchant);
                }
                owner.freezeOriginals(close,List.of(contribution),components);
                depositStage.persistCredits(credits);
            }
        });
        if(depositStage!=null && depositStage.splitOrders) assertEquals(3,count("coop_settlement_original_component"));
        else assertEquals(2,count("coop_settlement_original_component"));
        db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(901,'RF901','registration',1,1,11,1,0,?,10,0)",initialRefundAmount);
    }

    void terminalSuccessAndHook() {
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            db.update("UPDATE refund_application SET payout_status=1 WHERE id=901 AND payout_status=0");
            owner.reverseCompletedRegistrationRefund(901L);
            db.update("UPDATE wx_payment_callback_inbox SET state=2 WHERE id=901");
        });
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

    @Test void sameTransactionConsumedPaymentFreezesAndRefundsWithoutFabricatedPaymentInbox() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",false,true);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM wx_payment_callback_inbox WHERE id=900",Integer.class));
        assertEquals(originalPayment.id(),db.queryForObject("SELECT payment_fact_id FROM cms_registration_payment_fact WHERE registration_id=1",Long.class));
        assertEquals(originalPayment.hash(),db.queryForObject("SELECT payment_fact_hash FROM cms_registration_payment_fact WHERE registration_id=1",String.class));
        assertEquals(1,count("coop_settlement_cash_close"));
        assertEquals(2,count("coop_settlement_original_component"));
        terminalSuccessAndHook(); terminalSuccessAndHook();
        assertEquals(1,count("coop_settlement_refund_receipt"));
        assertEquals(2,count("coop_settlement_original_adjustment"));
        assertMoney("-10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
    }

    /** Storage branch isolation only: the extra head/component are rolled back, never paid or allocated. */
    @Test void consumedAssociationDisablesLegacyDoneFallbackAndExactConsumedBranchStillWrites() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        var consumed = P0AcceptanceSchema.sql(source,"CmsRegistrationPaymentFactMapper")
                .getMapper(CmsRegistrationPaymentFactMapper.class);
        long closeId=db.queryForObject("SELECT id FROM coop_settlement_cash_close WHERE topic_id=11",Long.class);
        // Both branches see the same real historical DONE/payment reference. A consumption takes precedence.
        for (boolean hasConsumption : List.of(false,true)) {
            tx.executeWithoutResult(status -> {
                db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
                db.queryForObject("SELECT id FROM cms_registration WHERE id=1 FOR UPDATE",Long.class);
                if (hasConsumption) assertEquals(1,consumed.insertConsumed(1L,originalPayment.id(),originalPayment.hash()));
                db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_close_id,cash_head_kind,line_type,payee_type,status,amount,del_flag) VALUES(13,11,7,?,'MEMBER','INITIATOR_GUARANTEE','initiator',0,1,0)",closeId);
                CoopSettlementOriginalComponent row=component(13L,7L,"MEMBER","INITIATOR_GUARANTEE","1");
                row.setCashCloseId(closeId);row.setTopicId(11L);row.setSourceFingerprint(HASH);
                if (hasConsumption) {
                    assertEquals(0,originals.insertComponent(row),"A receipt forbids fallback even to an otherwise matching historical DONE");
                    assertEquals(1,originals.insertConsumedPaymentComponent(row),"The exact consumed reference remains insertable");
                } else {
                    assertEquals(0,originals.insertConsumedPaymentComponent(row),"Missing A receipt cannot authorize the consumed branch");
                    assertEquals(1,originals.insertComponent(row),"Strict historical DONE remains usable without a new association");
                }
                assertEquals(originalPayment.id(),db.queryForObject("SELECT payment_fact_id FROM coop_settlement_original_component WHERE settlement_id=13",Long.class));
                assertEquals(originalPayment.hash(),db.queryForObject("SELECT payment_fact_hash FROM coop_settlement_original_component WHERE settlement_id=13",String.class));
                status.setRollbackOnly();
            });
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE id=13",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact",Integer.class));
            assertEquals(2,count("coop_settlement_original_component"));
        }
        assertEquals(0,count("coop_settlement_original_adjustment"));assertEquals(0,count("coop_settlement_refund_receipt"));
        assertMoney("10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement",BigDecimal.class));
        assertMoney("2",balance());assertEquals(0,count("ums_member_settlement_debt"));
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

    /** Typed persisted B facts and the original inbox binding, never a local success boolean. */
    WechatVerifiedPaymentFact.Reference prepareSuccessfulRefund(long refundId, BigDecimal amount) {
        String outRefundNo="RF"+refundId;
        var reference=facts.recordVerified(refund("wx-original",outRefundNo,"WXREF"+refundId,amount));
        db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,owner_type,owner_id,status,payout_status,refund_amount,paid_amount,del_flag) VALUES(?,?,'registration',1,1,11,1,0,?,10,0)",refundId,outRefundNo,amount);
        String hash=WxPaymentCallbackInboxServiceImpl.sha256Hex("REFUND:"+outRefundNo+":SUCCESS");
        db.update("INSERT INTO wx_payment_callback_inbox(id,notify_hash,biz_type,biz_id,event_type,state) VALUES(?,UNHEX(?),2,?,'SUCCESS',0)",refundId,hash,refundId);
        facts.bindNewInbox(refundId,hash,reference,true);
        return reference;
    }

    void terminalSuccessAndHook(long refundId) {
        tx.executeWithoutResult(status -> {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            db.update("UPDATE refund_application SET payout_status=1 WHERE id=? AND payout_status=0",refundId);
            owner.reverseCompletedRegistrationRefund(refundId);
            db.update("UPDATE wx_payment_callback_inbox SET state=2 WHERE id=?",refundId);
        });
    }

    @Test void eachMicroRefundHasExactEffectsAndFullRefundClearsFrozenRemaindersWithoutChangingHeads() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",true,false,money("0.01"));
        terminalSuccessAndHook();
        assertMoney("0.01",db.queryForObject("SELECT reversed_amount FROM coop_settlement_refund_receipt WHERE refund_id=901",BigDecimal.class));
        assertMoney("-0.01",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        prepareSuccessfulRefund(902L,money("0.01")); terminalSuccessAndHook(902L);
        prepareSuccessfulRefund(903L,money("9.98")); terminalSuccessAndHook(903L);
        assertEquals(3,count("coop_settlement_refund_receipt"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement_refund_receipt WHERE reversed_amount<>refund_amount",Integer.class));
        assertMoney("10",db.queryForObject("SELECT cumulative_after FROM coop_settlement_refund_receipt WHERE refund_id=903",BigDecimal.class));
        assertMoney("-10",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement_original_component c LEFT JOIN (SELECT component_id,-SUM(amount) AS reversed FROM coop_settlement_original_adjustment GROUP BY component_id) a ON a.component_id=c.id WHERE c.original_amount<>COALESCE(a.reversed,0)",Integer.class));
        int effects=count("coop_settlement_original_adjustment");
        long originalReceipt=db.queryForObject("SELECT id FROM coop_settlement_refund_receipt WHERE refund_id=901",Long.class);
        terminalSuccessAndHook(901L);
        assertEquals(originalReceipt,db.queryForObject("SELECT id FROM coop_settlement_refund_receipt WHERE refund_id=901",Long.class));
        assertEquals(3,count("coop_settlement_refund_receipt")); assertEquals(effects,count("coop_settlement_original_adjustment"));
        assertMoney("1",db.queryForObject("SELECT amount FROM coop_settlement WHERE id=11",BigDecimal.class));
        assertMoney("9",db.queryForObject("SELECT amount FROM coop_settlement WHERE id=12",BigDecimal.class));
        assertMoney("2",balance()); assertEquals(0,count("ums_member_settlement_debt"));
    }

    @Test void failedPartialWalletWriteCannotAdvanceCumulativeBasisReceiptOrInboxAndExactFactCanRetry() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(true,"wx-original",true,false,money("0.01"));
        terminalSuccessAndHook();
        assertMoney("1.99",balance());
        var second=prepareSuccessfulRefund(902L,money("0.02"));
        int effects=count("coop_settlement_original_adjustment");
        failAfterWalletReceipt.set(true);
        assertEquals("INJECTED_AFTER_REAL_WALLET_RECEIPT",assertThrows(IllegalStateException.class,
                ()->terminalSuccessAndHook(902L)).getMessage());
        assertMoney("1.99",balance()); assertEquals(1,count("coop_settlement_refund_receipt"));
        assertEquals(effects,count("coop_settlement_original_adjustment")); assertEquals(0,count("ums_member_settlement_debt"));
        assertEquals(0,db.queryForObject("SELECT payout_status FROM refund_application WHERE id=902",Integer.class));
        assertEquals(0,db.queryForObject("SELECT state FROM wx_payment_callback_inbox WHERE id=902",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94",Integer.class));
        assertEquals(second,facts.requireVerified(second).reference());
        failAfterWalletReceipt.set(false); terminalSuccessAndHook(902L);
        assertMoney("1.97",balance()); assertEquals(2,count("coop_settlement_refund_receipt"));
        assertMoney("0.01",db.queryForObject("SELECT cumulative_before FROM coop_settlement_refund_receipt WHERE refund_id=902",BigDecimal.class));
        assertMoney("0.03",db.queryForObject("SELECT cumulative_after FROM coop_settlement_refund_receipt WHERE refund_id=902",BigDecimal.class));
        assertMoney("0.02",db.queryForObject("SELECT reversed_amount FROM coop_settlement_refund_receipt WHERE refund_id=902",BigDecimal.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM ums_member_balance_detail WHERE event_type=94",Integer.class));
    }

    @Test void concurrentDistinctPartialSuccessesSerializeTheirCumulativeReceiptsAndReplayWithoutNewEffects() throws Exception {
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",true,false,money("0.01"));
        prepareSuccessfulRefund(902L,money("0.02"));
        ExecutorService workers=Executors.newFixedThreadPool(2); CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            Callable<Void> first=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));terminalSuccessAndHook(901L);return null;};
            Callable<Void> second=()->{ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS));terminalSuccessAndHook(902L);return null;};
            Future<Void> one=workers.submit(first),two=workers.submit(second);
            assertTrue(ready.await(5,TimeUnit.SECONDS)); start.countDown();
            one.get(20,TimeUnit.SECONDS); two.get(20,TimeUnit.SECONDS);
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(2,count("coop_settlement_refund_receipt"));
        var receipts=tx.execute(status -> originals.selectReceiptsForPayerForUpdate(
                db.queryForObject("SELECT id FROM coop_settlement_cash_close WHERE topic_id=11",Long.class),1L));
        BigDecimal cumulative=money("0");
        for(var receipt:receipts) {
            assertEquals(cumulative,receipt.getCumulativeBefore());
            assertEquals(receipt.getRefundAmount(),receipt.getReversedAmount());
            cumulative=cumulative.add(receipt.getRefundAmount()); assertEquals(cumulative,receipt.getCumulativeAfter());
        }
        assertEquals(money("0.03"),cumulative);
        assertMoney("-0.03",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        int effects=count("coop_settlement_original_adjustment");
        terminalSuccessAndHook(902L);terminalSuccessAndHook(901L);
        assertEquals(2,count("coop_settlement_refund_receipt"));assertEquals(effects,count("coop_settlement_original_adjustment"));
        assertMoney("2",balance());assertEquals(0,count("ums_member_settlement_debt"));
    }

    /** Real original head/claim/adjustment/invite SQL. Prepared contract admission is tested elsewhere. */
    private final class DepositSetoffStage {
        final java.util.SortedMap<Long,BigDecimal> owed = new java.util.TreeMap<>();
        final boolean deferred;
        boolean failAfterSetoff;
        boolean splitOrders;
        final List<CoopSettlement> sourceHeads=new ArrayList<>();
        final Map<Long,CoopSettlementDetail> sourceDetails=new HashMap<>();
        CmsTopicServiceImpl topicService;
        CoopSettlementClaimServiceImpl claimOwner;
        CoopSettlementMapper heads;
        CoopSettlement head;
        CoopSettlementDetail detail;
        DepositSetoffStage(String amount, boolean deferred) { owed.put(88L,money(amount));this.deferred=deferred; }
        void initialize(SqlSessionTemplate sql, CmsTopicMapper topics, UmsMemberMapper members,
                IUmsMemberBalanceDetailService balanceDetails, SettlementDebtLedger debts) {
            heads=sql.getMapper(CoopSettlementMapper.class);
            claimOwner=new CoopSettlementClaimServiceImpl(mock(com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader.class),
                    mock(com.chengyinhub.business.service.FulfillmentWindowResolver.class),mock(TopicSettlementEvidenceService.class),
                    new SettlementClaimServiceImpl(sql.getMapper(SettlementClaimMapper.class)),heads,
                    sql.getMapper(CoopSettlementDetailMapper.class),sql.getMapper(CoopSettlementAdjustmentMapper.class));
            ReflectionTestUtils.setField(claimOwner,"topicMapper",topics);
            topicService=new CmsTopicServiceImpl();
            ReflectionTestUtils.setField(topicService,"cmsTopicMapper",topics);
            ReflectionTestUtils.setField(topicService,"coopInviteMapper",sql.getMapper(CoopInviteMapper.class));
            ReflectionTestUtils.setField(topicService,"coopInviteService",new CoopInviteServiceImpl());
            ReflectionTestUtils.setField(topicService,"coopSettlementMapper",heads);
            ReflectionTestUtils.setField(topicService,"coopSettlementOriginalMapper",originals);
            ReflectionTestUtils.setField(topicService,"coopSettlementAdjustmentMapper",sql.getMapper(CoopSettlementAdjustmentMapper.class));
            ReflectionTestUtils.setField(topicService,"coopSettlementClaimService",claimOwner);
            ReflectionTestUtils.setField(topicService,"coopComplaintMapper",sql.getMapper(CoopComplaintMapper.class));
            ReflectionTestUtils.setField(topicService,"umsMemberMapper",members);
            ReflectionTestUtils.setField(topicService,"umsMemberBalanceDetailService",balanceDetails);
            ReflectionTestUtils.setField(topicService,"settlementDebtLedger",debts);
            db.update("INSERT INTO coop_order(id,topic_id,from_id,to_id,to_type,share_mode,share_rate,terms_version,status,del_flag) "
                    +"VALUES(701,11,7,7,'merchant',1,90,'original-v1',0,0)");
            if(splitOrders) {
                db.update("INSERT INTO cms_activity(id,member_id,topic_id,del_flag) VALUES(61,7,11,0)");
                db.update("INSERT INTO coop_order(id,topic_id,game_id,from_id,to_id,to_type,share_mode,share_rate,terms_version,status,del_flag) "
                        +"VALUES(702,11,61,7,7,'merchant',1,50,'original-v1',0,0)");
            }
            // Reverse insertion order deliberately; the production method must use original invite IDs.
            for(Long id:new java.util.TreeSet<>(owed.keySet()).descendingSet()) db.update(
                    "INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,status,deposit_status,deposit_amount,del_flag) "
                            +"VALUES(?,0,'talent',7,'merchant',7,11,1,5,?,0)",id,owed.get(id));
        }
        void persistOriginal() {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            db.queryForObject("SELECT id FROM cms_registration WHERE id=1 FOR UPDATE",Long.class);
            if(splitOrders) { persistLine(702L,"ACTIVITY",61L,"5");persistLine(701L,"TOPIC_EDITION",11L,"4"); }
            else persistLine(701L,"TOPIC_EDITION",11L,"9");
            head=sourceHeads.get(0);detail=sourceDetails.get(head.getId());
        }
        void persistLine(Long order,String context,Long contextId,String amount) {
            var snapshot=new com.chengyinhub.business.domain.dto.CoopSettlementSnapshot();
            snapshot.setTopicId(11L);snapshot.setOrderId(order);snapshot.setInitiatorMemberId(7L);
            snapshot.setCommercialContextType(context);snapshot.setCommercialContextId(contextId);
            snapshot.setBeneficiaryRole("MERCHANT");snapshot.setBeneficiaryId(7L);snapshot.setServiceUnit("MERCHANT_PARTICIPATION");
            var line=new com.chengyinhub.business.domain.dto.CoopSettlementOriginLine(1L,money(amount));line.setEligibleSalesSnapshot(money("10"));
            var plan=new com.chengyinhub.business.domain.dto.CoopSettlementPlan(snapshot,"COOP_ORDER",List.of(line));
            var row=new CoopSettlement();row.setTopicId(11L);row.setCoopOrderId(order);row.setMerchantId(7L);
            row.setCashHeadKind("MEMBER");row.setLineType("MERCHANT_VERIFY");row.setPayeeType("merchant");
            row.setStatus(0);row.setDelFlag(0);row.setAmount(money(amount));row.setCreateTime(new Date());
            row.setPayableTime(new Date(1700000000000L));
            claimOwner.persist(plan,row);sourceHeads.add(row);
            sourceDetails.put(row.getId(),originals.selectOriginalDetailsForHeadForUpdate(row.getId()).get(0));
        }
        List<CoopSettlement> apply() {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            var topic=new CmsTopic();topic.setId(11L);topic.setMemberId(7L);
            List<CoopSettlement> pending=new ArrayList<>(sourceHeads);
            ReflectionTestUtils.invokeMethod(topicService,"applyUnpaidDepositSetoffs",topic,pending,new Date(),new Date(),1,money("10"),"1");
            if(failAfterSetoff) throw new IllegalStateException("INJECTED_AFTER_REAL_SETOFF_AND_INVITE_CAS");
            return pending.stream().filter(row -> "DEPOSIT_DEDUCTION".equals(row.getLineType())).toList();
        }
        void persistCredits(List<CoopSettlement> credits) {
            for(CoopSettlement credit:credits) { assertEquals(1,heads.insertCoopSettlement(credit));assertNotNull(credit.getId()); }
        }
        void repeat() { tx.executeWithoutResult(status -> persistCredits(apply())); }
        BigDecimal net() { return tx.execute(status -> {
            db.queryForObject("SELECT id FROM cms_topic WHERE id=11 FOR UPDATE",Long.class);
            return ReflectionTestUtils.invokeMethod(topicService,"currentCoopHeadNet",heads.selectByIdForUpdate(head.getId()));
        }); }
    }

    @Test void unpaidDepositOriginalGrossAndReceiptFreezeAtomicallyThenPartialRefundUsesNet() throws Exception {
        depositStage=new DepositSetoffStage("5",false);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original",true,false,money("2"));
        assertMoney("9",db.queryForObject("SELECT amount FROM coop_settlement WHERE id=?",BigDecimal.class,depositStage.head.getId()));
        assertMoney("-5",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_adjustment",BigDecimal.class));
        assertMoney("5",db.queryForObject("SELECT SUM(amount) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",BigDecimal.class));
        assertMoney("4",depositStage.net());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM coop_settlement credit JOIN coop_settlement_adjustment a "
                +"ON credit.source_contract_type='DEPOSIT_SETOFF' AND credit.source_contract_id=a.id "
                +"JOIN coop_settlement_detail d ON d.claim_id=a.claim_id AND d.settlement_id=a.settlement_id "
                +"WHERE a.event_key=concat('UNPAID_DEPOSIT:11:88:701:',a.settlement_id)",Integer.class));
        assertMoney("9",db.queryForObject("SELECT original_amount FROM coop_settlement_original_component WHERE settlement_id=?",BigDecimal.class,depositStage.head.getId()));
        terminalSuccessAndHook();
        assertMoney("2.20",depositStage.net());
        assertMoney("-2",db.queryForObject("SELECT SUM(amount) FROM coop_settlement_original_adjustment",BigDecimal.class));
        int receipts=count("coop_settlement_adjustment");depositStage.repeat();terminalSuccessAndHook();
        assertEquals(receipts,count("coop_settlement_adjustment"));assertEquals(1,count("coop_settlement_refund_receipt"));
    }

    @Test void unpaidDepositPayoutUsesAdjustedNetOnceAndKeepsOriginalGross() throws Exception {
        depositStage=new DepositSetoffStage("5",false);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        Long headId=depositStage.head.getId();
        CoopSettlement paid=tx.execute(status -> ReflectionTestUtils.invokeMethod(depositStage.topicService,
                "payoutOneDueCoopSettlement",headId,11L,new Date()));
        assertNotNull(paid);assertEquals(headId,paid.getId());assertMoney("4",paid.getAmount());
        assertMoney("6",balance());assertMoney("9",db.queryForObject("SELECT amount FROM coop_settlement WHERE id=?",BigDecimal.class,headId));
        assertMoney("4",db.queryForObject("SELECT change_balance FROM ums_member_balance_detail WHERE event_type=5 AND event_id=?",BigDecimal.class,headId));
        assertEquals(1,db.queryForObject("SELECT status FROM coop_settlement WHERE id=?",Integer.class,headId));
        assertNull(tx.execute(status -> ReflectionTestUtils.invokeMethod(depositStage.topicService,
                "payoutOneDueCoopSettlement",headId,11L,new Date())));
        assertMoney("6",balance());assertEquals(1,count("ums_member_balance_detail"));
        assertEquals(1,count("coop_settlement_adjustment"));assertEquals(0,count("ums_member_settlement_debt"));
    }

    @Test void unpaidDepositInsufficientOriginalReceivableRetainsRemainingAndReplaysNoEffect() throws Exception {
        depositStage=new DepositSetoffStage("50",false);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        assertMoney("0",depositStage.net());
        assertMoney("41",db.queryForObject("SELECT deposit_amount FROM coop_invite WHERE id=88",BigDecimal.class));
        assertEquals(5,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=88",Integer.class));
        depositStage.repeat();depositStage.repeat();
        assertEquals(1,count("coop_settlement_adjustment"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",Integer.class));
        assertMoney("41",db.queryForObject("SELECT deposit_amount FROM coop_invite WHERE id=88",BigDecimal.class));
    }

    @Test void unpaidDepositZeroDebtKeepsAllMoneyAndCreatesNoReceipt() throws Exception {
        depositStage=new DepositSetoffStage("0",false);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        assertMoney("9",depositStage.net());assertEquals(0,count("coop_settlement_adjustment"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",Integer.class));
    }

    @Test void unpaidDepositMultipleInvitesUseStableOriginalIdOrderAndCannotOverdraw() throws Exception {
        depositStage=new DepositSetoffStage("7",false);depositStage.owed.put(87L,money("4"));
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        assertMoney("0",depositStage.net());
        assertMoney("-4",db.queryForObject("SELECT amount FROM coop_settlement_adjustment WHERE event_key LIKE 'UNPAID_DEPOSIT:11:87:%'",BigDecimal.class));
        assertMoney("-5",db.queryForObject("SELECT amount FROM coop_settlement_adjustment WHERE event_key LIKE 'UNPAID_DEPOSIT:11:88:%'",BigDecimal.class));
        assertMoney("2",db.queryForObject("SELECT deposit_amount FROM coop_invite WHERE id=88",BigDecimal.class));
        assertEquals(3,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=87",Integer.class));
        assertEquals(5,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=88",Integer.class));
    }

    @Test void unpaidDepositMultipleOriginalOrdersAllocateByFrozenOrderIdAndRetainGross() throws Exception {
        depositStage=new DepositSetoffStage("5",false);depositStage.splitOrders=true;
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        assertMoney("-4",db.queryForObject("SELECT amount FROM coop_settlement_adjustment WHERE event_key LIKE 'UNPAID_DEPOSIT:11:88:701:%'",BigDecimal.class));
        assertMoney("-1",db.queryForObject("SELECT amount FROM coop_settlement_adjustment WHERE event_key LIKE 'UNPAID_DEPOSIT:11:88:702:%'",BigDecimal.class));
        assertMoney("4",db.queryForObject("SELECT amount FROM coop_settlement WHERE coop_order_id=701",BigDecimal.class));
        assertMoney("5",db.queryForObject("SELECT amount FROM coop_settlement WHERE coop_order_id=702",BigDecimal.class));
        assertMoney("5",db.queryForObject("SELECT SUM(amount) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",BigDecimal.class));
        assertMoney("9",db.queryForObject("SELECT SUM(original_amount) FROM coop_settlement_original_component WHERE line_type='MERCHANT_VERIFY'",BigDecimal.class));
        depositStage.repeat();assertEquals(2,count("coop_settlement_adjustment"));
    }

    @Test void unpaidDepositFailureAfterAdjustmentAndCasRollsBackAllLocalEffects() throws Exception {
        depositStage=new DepositSetoffStage("5",true);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        depositStage.failAfterSetoff=true;
        assertThrows(IllegalStateException.class,depositStage::repeat);
        assertEquals(0,count("coop_settlement_adjustment"));
        assertEquals(5,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=88",Integer.class));
        assertMoney("5",db.queryForObject("SELECT deposit_amount FROM coop_invite WHERE id=88",BigDecimal.class));
        depositStage.failAfterSetoff=false;depositStage.repeat();
        assertMoney("4",depositStage.net());assertEquals(1,count("coop_settlement_adjustment"));
    }

    @Test void unpaidDepositConcurrentTransactionsWriteOneOriginalEventAndOnePlatformReceipt() throws Exception {
        depositStage=new DepositSetoffStage("5",true);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Void> apply=()->{assertTrue(start.await(5,TimeUnit.SECONDS));depositStage.repeat();return null;};
            Future<Void> one=workers.submit(apply),two=workers.submit(apply);start.countDown();
            one.get(20,TimeUnit.SECONDS);two.get(20,TimeUnit.SECONDS);
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(1,count("coop_settlement_adjustment"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",Integer.class));
        assertMoney("4",depositStage.net());
    }

    @Test void unpaidDepositCannotUseAnotherMembersOriginalReceivable() throws Exception {
        depositStage=new DepositSetoffStage("5",true);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        db.update("UPDATE coop_invite SET to_id=77 WHERE id=88");
        depositStage.repeat();
        assertEquals(0,count("coop_settlement_adjustment"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE line_type='DEPOSIT_DEDUCTION'",Integer.class));
        assertMoney("9",depositStage.net());assertMoney("5",db.queryForObject("SELECT deposit_amount FROM coop_invite WHERE id=88",BigDecimal.class));
        assertEquals(5,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=88",Integer.class));
        assertMoney("2",balance());
    }

    @Test void unpaidDepositUnknownPaymentRemainsBlockedWithZeroReceipts() throws Exception {
        depositStage=new DepositSetoffStage("5",true);
        applyNewSchemaAndFreezeOriginalOrder(false,"wx-original");
        db.update("UPDATE coop_invite SET deposit_status=1,deposit_out_trade_no='DEP88-ORIGINAL' WHERE id=88");
        assertThrows(IllegalStateException.class,depositStage::repeat);
        assertEquals(0,count("coop_settlement_adjustment"));
        assertEquals(1,db.queryForObject("SELECT deposit_status FROM coop_invite WHERE id=88",Integer.class));
        assertEquals("DEP88-ORIGINAL",db.queryForObject("SELECT deposit_out_trade_no FROM coop_invite WHERE id=88",String.class));
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
    static WechatVerifiedPaymentFact refund(String app) {return refund(app,"RF901","WXREF1",money("10"));}
    static WechatVerifiedPaymentFact refund(String app,String outRefundNo,String refundId,BigDecimal amount) {
        return new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.REFUND,"merchant",app,"REG1","WXP1",
                outRefundNo,refundId,"WXTX1","JSAPI","CNY",1000,amount.movePointRight(2).longValueExact(),
                Instant.ofEpochSecond(2),null,"REFUND");
    }
    int count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    BigDecimal balance() {return db.queryForObject("SELECT balance FROM ums_member WHERE id=7",BigDecimal.class);}
    static BigDecimal money(String value) {return new BigDecimal(value).setScale(2);}
    static void assertMoney(String expected,BigDecimal actual) {assertEquals(0,money(expected).compareTo(actual));}
}
