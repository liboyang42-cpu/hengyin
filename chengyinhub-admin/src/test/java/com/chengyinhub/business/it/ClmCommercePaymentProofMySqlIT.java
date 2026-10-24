package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.config.WxPayConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real canonical MySQL/XML/domain/proof/recovery transactions. Explicit controlled observations, no SDK/model/production. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClmCommercePaymentProofMySqlIT {
    private final String suite="epayproof_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private boolean ownedSchema;
    private TransactionTemplate transaction;
    private WechatVerifiedPaymentFactService facts;
    private ClubMembershipServiceImpl club;
    private MerchantCommerceService commerce;
    private ClubMembershipMapper invoices;
    private long invoiceId;
    private final String clmNo="CLM"+"a".repeat(29), commerceNo="TPLTM_PROOF_1";
    private static final String REQUEST_HASH="b".repeat(64);

    @BeforeAll void schemaAndActualOwners() throws Exception {
        assertEquals("github-hosted",System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1",System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        for(String name:List.of("CHENGYIN_MYBATIS_IT_PORT","CHENGYIN_MYBATIS_IT_USER","CHENGYIN_MYBATIS_IT_PASSWORD"))
            assertNotNull(System.getenv(name),"explicit scratch connection selector required: "+name);
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        int port=Integer.parseInt(MybatisItDatabase.port());assertTrue(port>0 && port<65536);
        String schema=MybatisItDatabase.databaseName(suite);assertTrue(schema.matches("[a-z0-9_]{1,64}"));
        String endpoint="jdbc:mysql://"+MybatisItDatabase.host()+":"+port+"/";
        var admin=new JdbcTemplate(new DriverManagerDataSource(endpoint+"?useSSL=false&allowPublicKeyRetrieval=true",
            MybatisItDatabase.user(),MybatisItDatabase.password()));
        admin.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");ownedSchema=true;
        source=new DriverManagerDataSource(endpoint+schema+"?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
            MybatisItDatabase.user(),MybatisItDatabase.password());db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        P0AcceptanceSchema.baseTables(db,"sys_config","ums_member","club","club_member","oms_order","merchant_subscription");
        P0AcceptanceSchema.migrations(source,"migration_club_open_settings_20260909.sql","migration_club_role_membership_20260823.sql",
            "migration_club_role_membership_resume_fence_20260826.sql","migration_20260821_commerce_order_idempotency.sql",
            "migration_business_audit_event_20260823.sql","migration_20261023_wx_pay_order_intent.sql",
            "migration_wx_payment_callback_inbox_outbox_20260725.sql","migration_20261024_wx_verified_payment_fact.sql",
            "migration_20261024_clm_commerce_payment_proof.sql");
        // As in ClubJoinGovernanceMySqlIT, apply the canonical audit-table evolution after
        // the original audit migration has completed its strict baseline checks. This suite
        // does not install unrelated approval/menu tables or rewrite the canonical ALTER.
        String governance = java.nio.file.Files.readString(P0AcceptanceSchema.repoRoot()
            .resolve("chengyinhub-admin/sql/migration_approval_governance_20260828.sql"),
            java.nio.charset.StandardCharsets.UTF_8);
        var auditAlter = java.util.regex.Pattern.compile("(?s)ALTER TABLE business_audit_event\\s+.*?;")
            .matcher(governance);
        assertTrue(auditAlter.find(), "canonical audit actor/request identity migration must exist");
        db.execute(auditAlter.group());
        assertFalse(auditAlter.find(), "the canonical audit-table evolution must be unambiguous");
        SqlSessionTemplate sql=P0AcceptanceSchema.sql(source,"ClubMapper","ClubMemberMapper","ClubMembershipMapper",
            "OmsOrderMapper","MerchantSubscriptionMapper","BusinessAuditEventMapper");
        invoices=sql.getMapper(ClubMembershipMapper.class);
        facts=WechatVerifiedFactTestSupport.realFacts(source);
        var manager=new DataSourceTransactionManager(source);transaction=new TransactionTemplate(manager);
        var owner=new JdbcWechatPaymentOrderOwner();ReflectionTestUtils.setField(owner,"paymentIntentJdbc",db);
        var intents=new WechatPaymentIntentStore();ReflectionTestUtils.setField(intents,"paymentIntentJdbc",db);
        ReflectionTestUtils.setField(intents,"paymentIntentTransactions",manager);ReflectionTestUtils.setField(intents,"businessOwner",owner);
        ReflectionTestUtils.setField(intents,"verifiedFacts",facts);
        var gateway=new WechatPaymentSdkGateway();var config=mock(WxPayConfig.class);when(config.getMerchantId()).thenReturn("merchant");
        ReflectionTestUtils.setField(gateway,"wxPayConfig",config);ReflectionTestUtils.setField(gateway,"intents",intents);
        // Only the local original-attempt lock port is invoked; no client is constructed/initialized or called.
        club=new ClubMembershipServiceImpl();
        ReflectionTestUtils.setField(club,"clubMapper",sql.getMapper(ClubMapper.class));
        ReflectionTestUtils.setField(club,"clubMemberMapper",sql.getMapper(ClubMemberMapper.class));
        ReflectionTestUtils.setField(club,"clubMembershipMapper",invoices);
        ReflectionTestUtils.setField(club,"businessAuditEventMapper",sql.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(club,"transactionManager",manager);ReflectionTestUtils.setField(club,"wechatPayService",gateway);
        ReflectionTestUtils.setField(club,"verifiedPaymentFacts",facts);
        commerce=new MerchantCommerceService(false);
        ReflectionTestUtils.setField(commerce,"orderMapper",sql.getMapper(OmsOrderMapper.class));
        ReflectionTestUtils.setField(commerce,"subscriptionMapper",sql.getMapper(MerchantSubscriptionMapper.class));
        ReflectionTestUtils.setField(commerce,"paymentTransactions",manager);ReflectionTestUtils.setField(commerce,"wechatPayService",gateway);
        ReflectionTestUtils.setField(commerce,"verifiedPaymentFacts",facts);
    }
    @BeforeEach void seedOriginalScope() {
        // Drop/delete only rows in this suite's exclusive scratch schema; proof FK order is explicit.
        for(String table:List.of("club_membership_refund_review","club_membership_term","club_membership_invoice","oms_order",
                "business_audit_event","merchant_subscription","wx_payment_callback_fact_link","wx_verified_payment_fact_rejection",
                "wx_payment_callback_outbox","wx_payment_callback_inbox","wx_verified_payment_fact","wx_pay_order_intent",
                "club_membership_plan","club_membership_setting","club_member","club","ums_member")) db.update("DELETE FROM "+table);
        db.update("INSERT INTO ums_member(id) VALUES(70),(71)");
        db.update("INSERT INTO club(id,member_id,name,status,del_flag) VALUES(7,70,'proof fixture',1,0)");
        db.update("INSERT INTO club_membership_plan(id,club_id,name,duration_days,price_fen,benefits_json,grace_days,past_due_days,"
            +"exit_policy,refund_policy,status,version,created_by_member_id,create_time) VALUES(9,7,'original',30,9900,'[]',0,30,"
            +"'END_IMMEDIATELY','MANUAL_REVIEW','ACTIVE',1,70,NOW())");
        ClubMembershipInvoice invoice=invoice();assertEquals(1,invoices.insertInvoice(invoice));invoiceId=invoice.getId();
        db.update("INSERT INTO oms_order(id,order_sn,member_id,mms_merchant_id,biz_type,pay_amount,status,pay_type,entitlement_days,entitlement_max_usage) "
            +"VALUES(8,?,70,34,'premium_template',19.90,0,0,30,3)",commerceNo);
        for(String no:List.of(clmNo,commerceNo)) {
            db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) "
                +"VALUES('merchant',?,?,?, ?,1,'wx-original','JSAPI','CNY',?,?,'','','https://example.test/notify')",
                no,no,no.equals(clmNo)?"CLM":"COMMERCE",no.equals(clmNo)?invoiceId:8L,no.equals(clmNo)?9900L:1990L,REQUEST_HASH);
        }
    }
    @AfterAll void cleanup() { if(ownedSchema && source!=null)P0AcceptanceSchema.drop(new JdbcTemplate(source),suite); }
    private ClubMembershipInvoice invoice() {
        var row=new ClubMembershipInvoice();row.setId(100L);row.setInvoiceNo(clmNo);row.setOutTradeNo(clmNo);
        row.setClubId(7L);row.setOwnerMemberIdSnapshot(70L);row.setMemberId(70L);row.setPlanId(9L);row.setPlanVersionSnapshot(1);
        row.setPlanNameSnapshot("original");row.setDurationDaysSnapshot(30);row.setAmountFen(9900L);row.setBenefitsSnapshot("[]");
        row.setGraceDaysSnapshot(0);row.setPastDueDaysSnapshot(30);row.setExitPolicySnapshot("END_IMMEDIATELY");
        row.setRefundPolicySnapshot("MANUAL_REVIEW");row.setStatus("PAYING");row.setIdempotencyKey("original-prepare");
        row.setRequestFingerprint("c".repeat(64));row.setVersion(0);row.setCreateTime(new Date());
        row.setSnapshotHash(ClubMembershipSnapshotCodec.hash(row));return row;
    }
    private Reference observe(String no,String tx) {
        return facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
            no,no,no,tx,tx,"JSAPI","CNY",no.equals(clmNo)?9900:1990,no.equals(clmNo)?9900:1990,
            Instant.parse("2026-10-24T00:00:00Z"),REQUEST_HASH,"SUCCESS"));
    }
    private boolean consume(boolean isClub,Reference ref,String tx) {
        return isClub?club.handlePaidInvoice(clmNo,true,9900,tx,ref):commerce.handlePaidOrder(commerceNo,true,1990,tx,ref);
    }
    private int count(String table) {return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private String table(boolean isClub){return isClub?"club_membership_invoice":"oms_order";}
    private long id(boolean isClub){return isClub?invoiceId:8;}
    private void quarantine(Reference ref,long businessId) {
        // Explicit adversarial/manual handoff fixture; production never fabricates or auto-reopens this queue.
        db.update("INSERT INTO wx_payment_callback_outbox(idempotency_key,biz_type,biz_id,state,money_affecting,last_error_code,create_time,update_time) "
            +"VALUES(?,1,?,4,0,'FIXTURE_ORIGINAL_SUCCESS',NOW(),NOW())","VERIFIED:"+ref.id()+":"+ref.hash(),businessId);
    }

    @Test void bothOwnersBindOriginalProofAndReplayWithoutAnotherGrant() {
        var c=observe(clmNo,"TX-C");var m=observe(commerceNo,"TX-M");
        assertTrue(consume(true,c,"TX-C"));assertTrue(consume(false,m,"TX-M"));
        assertEquals(1,count("club_membership_term"));assertEquals(1,count("merchant_subscription"));
        assertTrue(consume(true,c,"TX-C"));assertTrue(consume(false,m,"TX-M"));
        assertEquals(1,count("club_membership_term"));assertEquals(1,count("merchant_subscription"));
        assertEquals(c.hash(),db.queryForObject("SELECT CONVERT(verified_payment_fact_hash USING ascii) FROM club_membership_invoice",String.class));
        assertEquals(m.id(),db.queryForObject("SELECT verified_payment_fact_id FROM oms_order",Long.class));
    }
    @Test void originalMutationProofAndRecoveryCompletionRollbackTogetherInOuterCallbackTransaction() {
        for(boolean isClub:new boolean[]{true,false}) {
            Reference ref=observe(isClub?clmNo:commerceNo,isClub?"TX-C":"TX-M");quarantine(ref,id(isClub));
            assertThrows(IllegalStateException.class,()->transaction.execute(status->{
                assertTrue(consume(isClub,ref,isClub?"TX-C":"TX-M"));
                assertEquals(2,db.queryForObject("SELECT state FROM wx_payment_callback_outbox WHERE idempotency_key=?",Integer.class,
                    "VERIFIED:"+ref.id()+":"+ref.hash())); // Same callback connection sees its own uncommitted completion.
                throw new IllegalStateException("controlled callback-DONE failure");
            }));
            assertNull(db.queryForObject("SELECT verified_payment_fact_id FROM "+table(isClub)+" WHERE id=?",Long.class,id(isClub)));
            assertEquals(isClub?"PAYING":"0",db.queryForObject("SELECT CAST(status AS CHAR) FROM "+table(isClub)+" WHERE id=?",String.class,id(isClub)));
            assertEquals(4,facts.findPaymentRecovery(ref).orElseThrow().state());
            assertEquals(0,count(isClub?"club_membership_term":"merchant_subscription"));
        }
    }
    @Test void exactBoundReplayCompletesOnlyItsOriginalTicketAndNeverRegrants() {
        Reference ref=observe(commerceNo,"TX-M");assertTrue(consume(false,ref,"TX-M"));quarantine(ref,8);
        assertTrue(consume(false,ref,"TX-M"));assertEquals(2,facts.findPaymentRecovery(ref).orElseThrow().state());
        assertEquals(1,count("merchant_subscription"));assertTrue(consume(false,ref,"TX-M"));
        assertEquals(2,facts.findPaymentRecovery(ref).orElseThrow().state());
    }
    @Test void legacyPaidNullAndClosedNeverGainProofOrCompleteRecovery() {
        var c=observe(clmNo,"TX-C");var m=observe(commerceNo,"TX-M");quarantine(c,invoiceId);quarantine(m,8);
        db.update("UPDATE club_membership_invoice SET status='PAID',transaction_id='TX-C',paid_at=NOW() WHERE id=?",invoiceId);
        db.update("UPDATE oms_order SET status=1,payment_transaction_id='TX-M' WHERE id=8");
        assertFalse(consume(true,c,"TX-C"));assertFalse(consume(false,m,"TX-M"));
        assertEquals(4,facts.findPaymentRecovery(c).orElseThrow().state());assertEquals(4,facts.findPaymentRecovery(m).orElseThrow().state());
        db.update("UPDATE club_membership_invoice SET status='CLOSED',transaction_id=NULL,paid_at=NULL WHERE id=?",invoiceId);
        db.update("UPDATE oms_order SET status=5,payment_transaction_id=NULL WHERE id=8");
        assertFalse(consume(true,c,"TX-C"));assertFalse(consume(false,m,"TX-M"));
        assertEquals(0,count("club_membership_term"));assertEquals(0,count("merchant_subscription"));
    }
    @Test void foreignOrderReferenceWrongAmountAndWrongHashCannotConsume() {
        var c=observe(clmNo,"TX-C");var m=observe(commerceNo,"TX-M");
        assertThrows(IllegalStateException.class,()->consume(true,m,"TX-M"));
        assertThrows(IllegalStateException.class,()->commerce.handlePaidOrder(commerceNo,true,1991,"TX-M",m));
        assertThrows(IllegalStateException.class,()->consume(false,new Reference(m.id(),"0".repeat(64)),"TX-M"));
        assertEquals(0,count("club_membership_term"));assertEquals(0,count("merchant_subscription"));
        assertNull(db.queryForObject("SELECT verified_payment_fact_id FROM club_membership_invoice",Long.class));
        // Actual B ticket identity conflict occurs after domain CAS/grant/proof; all local effects must roll back.
        for(boolean isClub:new boolean[]{true,false}) {
            Reference ref=isClub?c:m;quarantine(ref,id(isClub)+999);
            assertThrows(WechatVerifiedPaymentFactService.ConflictException.class,()->consume(isClub,ref,isClub?"TX-C":"TX-M"));
            assertNull(db.queryForObject("SELECT verified_payment_fact_id FROM "+table(isClub)+" WHERE id=?",Long.class,id(isClub)));
            assertEquals(4,facts.findPaymentRecovery(ref).orElseThrow().state());
            assertEquals(0,count(isClub?"club_membership_term":"merchant_subscription"));
        }
    }
    @Test void databaseFkPairLengthCaseAndOneFactPerOrderConstraintsAreReal() {
        for(boolean isClub:new boolean[]{true,false}) {
            Reference ref=observe(isClub?clmNo:commerceNo,isClub?"TX-C":"TX-M");String table=table(isClub);
            constraint(3819,()->db.update("UPDATE "+table+" SET verified_payment_fact_id=?",ref.id()));
            for(String bad:List.of("a".repeat(63),"A".repeat(64)))constraint(3819,()->db.update(
                "UPDATE "+table+" SET verified_payment_fact_id=?,verified_payment_fact_hash=CAST(? AS BINARY)",ref.id(),bad));
            constraint(1452,()->db.update("UPDATE "+table+" SET verified_payment_fact_id=999999,verified_payment_fact_hash=CAST(? AS BINARY)",ref.hash()));
            assertTrue(consume(isClub,ref,isClub?"TX-C":"TX-M"));
            long otherId;
            if(isClub){var other=invoice();other.setOutTradeNo("CLM"+"d".repeat(29));other.setInvoiceNo(other.getOutTradeNo());
                other.setIdempotencyKey("second-original");assertEquals(1,invoices.insertInvoice(other));otherId=other.getId();}
            else{db.update("INSERT INTO oms_order(id,order_sn,member_id,mms_merchant_id,biz_type,status) VALUES(9,'TPLTM_PROOF_2',70,34,'premium_template',0)");otherId=9;}
            constraint(1062,()->db.update("UPDATE "+table+" SET verified_payment_fact_id=?,verified_payment_fact_hash=CAST(? AS BINARY) WHERE id=?",ref.id(),ref.hash(),otherId));
        }
    }
    @Test void latestClosedOriginalSuccessRetainsItsFactAndManualTicketWithoutLocalConsumption() {
        for(boolean isClub:new boolean[]{true,false}) {
            String no=isClub?clmNo:commerceNo,tx=isClub?"TX-C":"TX-M";
            db.update("UPDATE wx_pay_order_intent SET closed_confirmed_at=NOW(6) WHERE business_out_trade_no=?",no);
            Reference ref=observe(no,tx);var before=localEffects(isClub);
            assertEquals("VERIFIED_SUCCESS_AFTER_CLOSED",facts.findPaymentRecovery(ref).orElseThrow().reason());
            // Before the shared consumable fence the typed domain entry grants against this latest CLOSED row.
            assertFalse(consume(isClub,ref,tx));assertEquals(before,localEffects(isClub));
            assertEquals(ref,facts.requireVerified(ref).reference());assertEquals(4,facts.findPaymentRecovery(ref).orElseThrow().state());
            // A later explicitly frozen attempt also cannot consume the original old Reference.
            db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) "
                +"SELECT mchid,?,business_out_trade_no,biz_type,biz_id,2,appid,trade_type,currency,total_fen,?,'','','https://example.test/notify' "
                +"FROM wx_pay_order_intent WHERE business_out_trade_no=? AND attempt_no=1",
                "WXP_"+(isClub?"CLM":"COMMERCE")+"_"+id(isClub),"d".repeat(64),no);
            assertFalse(consume(isClub,ref,tx));assertEquals(before,localEffects(isClub));
            assertEquals(4,facts.findPaymentRecovery(ref).orElseThrow().state());
        }
    }
    @Test void boundPaidReplayCannotEraseALaterClosedAttemptManualHandoff() {
        for(boolean isClub:new boolean[]{true,false}) {
            String no=isClub?clmNo:commerceNo,tx=isClub?"TX-C":"TX-M";
            Reference ref=observe(no,tx);assertTrue(consume(isClub,ref,tx));
            // Adversarial scratch marker, not a newly authorized production close/second-charge policy.
            db.update("UPDATE wx_pay_order_intent SET closed_confirmed_at=NOW(6) WHERE business_out_trade_no=?",no);
            assertEquals(ref,observe(no,tx));var before=localEffects(isClub);
            assertEquals("VERIFIED_SUCCESS_AFTER_CLOSED",facts.findPaymentRecovery(ref).orElseThrow().reason());
            assertFalse(consume(isClub,ref,tx));assertEquals(before,localEffects(isClub));
            assertEquals(4,facts.findPaymentRecovery(ref).orElseThrow().state());assertEquals(ref,facts.requireVerified(ref).reference());
            assertEquals(ref.hash(),db.queryForObject("SELECT CONVERT(verified_payment_fact_hash USING ascii) FROM "+table(isClub)+" WHERE id=?",String.class,id(isClub)));
        }
    }
    private List<Object> localEffects(boolean isClub) {
        String selected=isClub?"status,version,transaction_id,paid_at":"status,payment_transaction_id,payment_time";
        return List.of(db.queryForMap("SELECT "+selected+",verified_payment_fact_id,CONVERT(verified_payment_fact_hash USING ascii) AS proof_hash FROM "+table(isClub)+" WHERE id=?",id(isClub)),
            db.queryForList("SELECT * FROM "+(isClub?"club_membership_term":"merchant_subscription")+" ORDER BY id"),count("business_audit_event"));
    }

    private void constraint(int mysqlCode,Runnable mutation) {
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,mutation::run);
        var cause=assertInstanceOf(java.sql.SQLException.class,failure.getMostSpecificCause());assertEquals(mysqlCode,cause.getErrorCode());
    }
}
