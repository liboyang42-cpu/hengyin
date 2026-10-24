package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CoopSettlementCashClose;
import com.chengyinhub.business.domain.CoopSettlementCashContribution;
import com.chengyinhub.business.domain.CoopSettlementCashEvent;
import com.chengyinhub.business.mapper.CoopSettlementCashEvidenceMapper;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** 真MySQL结构/原证据事务测试；不执行结算算法、余额、退款派发或调度。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Wp04CashEvidenceExpandMySqlIT {
    private static final String CLOSE="coop_settlement_cash_close";
    private static final String CONTRIBUTION="coop_settlement_cash_contribution";
    private static final String EVENT="coop_settlement_cash_event";
    private static final String HASH="a".repeat(64);
    private static final List<String> BASE_TABLES=List.of("ums_member","cms_topic","cms_registration",
            "coop_settlement","coop_settlement_adjustment","settlement_claim","coop_settlement_detail","refund_application");
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private CoopSettlementCashEvidenceMapper mapper;
    private Path sql;
    private String createdDatabase;

    @BeforeAll void requireExplicitControlledEnvironment() {
        for (String key:List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX"))
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+key),"必须显式配置测试连接项："+key);
        String host=System.getenv("CHENGYIN_MYBATIS_IT_HOST");
        assertTrue("127.0.0.1".equals(host)||"localhost".equals(host));
        int port=Integer.parseInt(System.getenv("CHENGYIN_MYBATIS_IT_PORT"));
        assertTrue(port>0&&port<=65535); assertFalse(System.getenv("CHENGYIN_MYBATIS_IT_USER").isBlank());
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("[A-Za-z0-9_]*"));
    }
    @BeforeEach void ownUuidSchemaAndRealLegacyDdl() throws Exception {
        String suite="g_cash_expand_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        assertTrue(MybatisItDatabase.databaseName(suite).length()<=64);
        String url=MybatisItDatabase.url(suite);
        createdDatabase=MybatisItDatabase.databaseName(suite); // recorded only after CREATE succeeded.
        source=new DriverManagerDataSource(url,MybatisItDatabase.user(),MybatisItDatabase.password());
        db=new JdbcTemplate(source); assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        Path root=Files.isRegularFile(Path.of("sql/base_schema_20260818.sql"))?Path.of(""):Path.of("..");
        sql=root.resolve("chengyinhub-admin/sql");
        String baseline=Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        for (String table:BASE_TABLES) {
            java.util.regex.Matcher ddl=Pattern.compile("(?s)CREATE TABLE `"+table+"` \\(.*?\\) ENGINE=.*?;").matcher(baseline);
            assertTrue(ddl.find(),table); db.execute(ddl.group());
        }
        db.update("INSERT INTO ums_member(id,balance) VALUES(77,50),(88,20),(101,0)");
        db.update("INSERT INTO cms_topic(id,member_id,fee_platform_rate,fee_guarantee_base_rate,fee_guarantee_step_rate,fee_guarantee_max_merchant)"
                +" VALUES(11,77,0.10,0.05,0.02,5),(22,77,0.10,0.05,0.02,5),(33,77,0.10,0.05,0.02,5),(99,77,0.10,0.05,0.02,5)");
        registration(101,11,"10.00",null,2); registration(102,11,"90.00",null,0);
        registration(201,22,"20.00",null,2);
        registration(302,33,"100.00","PARENT-302",2); registration(301,33,"0.00","PARENT-302",2);
        registration(9001,99,"7.00",null,2);
        db.update("INSERT INTO coop_settlement(id,topic_id,coop_order_id,merchant_id,amount,status,line_type,payee_type)"
                +" VALUES(1,99,700,88,7,0,'MERCHANT_VERIFY','merchant')");
        claimAndDetail(71,99,9001,700,1,"7.00");
        db.update("INSERT INTO coop_settlement_adjustment(id,settlement_id,claim_id,entry_kind,event_key,amount,status,create_time)"
                +" VALUES(1,1,71,'ADJUSTMENT','legacy-original-event',-1,'EXECUTED','2026-10-01 00:00:00')");
        db.update("INSERT INTO refund_application(id,refund_no,source_type,source_id,status,refund_amount,del_flag)"
                +" VALUES(501,'G-REFUND-501','registration',101,1,5,0),(502,'G-REFUND-502','registration',201,1,5,0)");
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setTimeout(10);
    }
    @AfterEach void dropOnlyThisTestUuidSchema() throws Exception {
        if (createdDatabase==null) return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_cash_expand_[a-f0-9]{12}[A-Za-z0-9_]*"));
        try (Connection connection=source.getConnection(); Statement statement=connection.createStatement()) {
            assertEquals(createdDatabase,connection.getCatalog());
            statement.execute("DROP DATABASE `"+createdDatabase+"`");
            try (PreparedStatement check=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                check.setString(1,createdDatabase);
                try (ResultSet rows=check.executeQuery()) { assertTrue(rows.next()); assertEquals(0,rows.getInt(1)); }
            }
        }
        createdDatabase=null; // DriverManagerDataSource has no pool; all connections closed.
    }

    @Test void zeroSalesCloseNeedsNeitherPositiveAmountNorInventedMemberRateOrHead() throws Exception {
        expand();
        db.update("UPDATE cms_topic SET member_id=NULL,fee_platform_rate=NULL,fee_guarantee_base_rate=NULL,"
                +"fee_guarantee_step_rate=NULL,fee_guarantee_max_merchant=NULL WHERE id=22");
        db.update("UPDATE cms_registration SET payment_status=0,registration_status=1 WHERE id=201");
        CoopSettlementCashClose zero=zeroClose(22,0); zero.setInitiatorMemberId(null);
        zero.setPlatformRate(null); zero.setGuaranteeBaseRate(null); zero.setGuaranteeStepRate(null); zero.setGuaranteeMaxMerchant(null);
        CoopSettlementCashClose saved=fenced(22,()->mapper.recordClose(zero));
        assertEquals("ZERO_SALES",saved.getCloseKind()); assertNull(saved.getInitiatorMemberId());
        assertNull(saved.getPlatformRate()); assertEquals(0,saved.getOriginalSalesBasis().signum());
        assertEquals(0,saved.getOriginalContributionCount()); assertEquals(0,count(CONTRIBUTION)); assertEquals(0,count(EVENT));
        assertEquals(1,count("coop_settlement"),"仅原有真实旧头；零销售不要求假头或平台会员");
        assertLegacy();
    }
    @Test void zeroCloseCanRetainWaitingOriginalOrderWithoutAddingItsPayableAmountToOriginalSales() throws Exception {
        expand();
        db.update("UPDATE cms_registration SET payment_status=0,registration_status=1 WHERE id=201");
        CoopSettlementCashClose zero=fenced(22,()->mapper.recordClose(zeroClose(22,1)));
        CoopSettlementCashContribution waiting=contribution(zero.getId(),201,22,"0.00");
        waiting.setSnapshotPaymentStatus(0); waiting.setSnapshotRegistrationStatus(1);
        waiting.setSnapshotPayableAmount(new BigDecimal("20.00"));
        waiting.setRegistrationSnapshot("{\"paymentStatus\":0,\"registrationStatus\":1,\"verificationStatus\":0}");
        CoopSettlementCashContribution original=fenced(22,()->mapper.recordContribution(waiting));
        assertEquals(0,original.getSalesBasisAmount().signum());
        assertEquals(0,new BigDecimal("20.00").compareTo(original.getSnapshotPayableAmount()));
        // Structural source mutation only: not a gateway, callback or completed payment test.
        db.update("UPDATE cms_registration SET payment_status=2,registration_status=2,transaction_id='TEST-ORIGINAL-201' WHERE id=201");
        assertEquals(original.getId(),fenced(22,()->mapper.recordContribution(waiting)).getId());
        assertEquals(0,db.queryForObject("SELECT original_sales_basis FROM "+CLOSE+" WHERE id=?",BigDecimal.class,zero.getId()).signum());
        assertEquals(0,db.queryForObject("SELECT sales_basis_amount FROM "+CONTRIBUTION+" WHERE id=?",BigDecimal.class,original.getId()).signum());
        assertThrows(IllegalStateException.class,()->fenced(22,()->mapper.recordContribution(contribution(zero.getId(),201,22,"20.00"))),
                "后到状态不能覆盖原贡献快照；完整支付原事实事件仍待A/B合同接线");
        assertEquals(0,count(EVENT)); assertEquals(1,count("coop_settlement")); assertLegacy();
    }
    @Test void zeroPositiveAndNullCloseKindsCannotMislabelFrozenBasis() throws Exception {
        expand();
        CoopSettlementCashClose zeroPositive=close(11); zeroPositive.setCloseKind("ZERO_SALES");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(zeroPositive)));
        CoopSettlementCashClose positiveZero=zeroClose(11,0); positiveZero.setCloseKind("POSITIVE_SALES");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(positiveZero)));
        CoopSettlementCashClose nullKind=zeroClose(11,0); nullKind.setCloseKind(null);
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(nullKind)));
        CoopSettlementCashClose negative=zeroClose(11,0); negative.setOriginalSalesBasis(new BigDecimal("-1.00"));
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(negative)));
        assertEquals(0,count(CLOSE)); assertLegacy();
    }
    @Test void originalAllStateSnapshotPreservesClosedAndNullStatesInsteadOfCallingThemPaid() throws Exception {
        expand();
        db.update("UPDATE cms_registration SET payment_status=4,registration_status=3,verification_status=NULL WHERE id=201");
        CoopSettlementCashClose zero=fenced(22,()->mapper.recordClose(zeroClose(22,1)));
        CoopSettlementCashContribution closed=contribution(zero.getId(),201,22,"0.00");
        closed.setSnapshotPaymentStatus(4); closed.setSnapshotRegistrationStatus(3); closed.setSnapshotVerificationStatus(null);
        closed.setSnapshotPayableAmount(new BigDecimal("20.00"));
        closed.setRegistrationSnapshot("{\"paymentStatus\":4,\"registrationStatus\":3,\"verificationStatus\":null}");
        CoopSettlementCashContribution saved=fenced(22,()->mapper.recordContribution(closed));
        assertEquals(4,saved.getSnapshotPaymentStatus()); assertEquals(3,saved.getSnapshotRegistrationStatus());
        assertNull(saved.getSnapshotVerificationStatus()); assertEquals(0,saved.getSalesBasisAmount().signum());
        assertEquals(1,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void paidOriginalSnapshotCannotInventZeroBasisOrClaimDifferentCurrentState() throws Exception {
        expand(); CoopSettlementCashClose c=fenced(11,()->mapper.recordClose(close(11)));
        CoopSettlementCashContribution zero=contribution(c.getId(),101,11,"0.00"); zero.setSnapshotPayableAmount(new BigDecimal("10.00"));
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(zero)));
        CoopSettlementCashContribution wrongStatus=contribution(c.getId(),101,11,"0.00");
        wrongStatus.setSnapshotPayableAmount(new BigDecimal("10.00")); wrongStatus.setSnapshotPaymentStatus(0);
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(wrongStatus)));
        assertEquals(0,count(CONTRIBUTION)); assertLegacy();
    }

    @Test void positiveCloseRequiresActualNonNullInitiatorAndEveryFrozenRate() throws Exception {
        expand();
        String[] fields={"fee_platform_rate","fee_guarantee_base_rate","fee_guarantee_step_rate","fee_guarantee_max_merchant"};
        String[] originals={"0.10","0.05","0.02","5"};
        for (int index=0;index<fields.length;index++) {
            db.update("UPDATE cms_topic SET "+fields[index]+"=NULL WHERE id=11");
            CoopSettlementCashClose incomplete=close(11);
            switch (index) {
                case 0 -> incomplete.setPlatformRate(null);
                case 1 -> incomplete.setGuaranteeBaseRate(null);
                case 2 -> incomplete.setGuaranteeStepRate(null);
                case 3 -> incomplete.setGuaranteeMaxMerchant(null);
                default -> fail("unexpected fixture field");
            }
            assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(incomplete)),fields[index]);
            db.update("UPDATE cms_topic SET "+fields[index]+"=? WHERE id=11",new BigDecimal(originals[index]));
        }
        db.update("UPDATE cms_topic SET member_id=NULL WHERE id=11");
        CoopSettlementCashClose missingOwner=close(11); missingOwner.setInitiatorMemberId(null);
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(missingOwner)));
        assertEquals(0,count(CLOSE)); assertLegacy();
    }
    @Test void zeroCloseCannotReplaceActualUnknownOwnerAndRatesWithInventedDefaults() throws Exception {
        expand();
        db.update("UPDATE cms_topic SET member_id=NULL,fee_platform_rate=NULL,fee_guarantee_base_rate=NULL,"
                +"fee_guarantee_step_rate=NULL,fee_guarantee_max_merchant=NULL WHERE id=22");
        CoopSettlementCashClose invented=zeroClose(22,0);
        invented.setPlatformRate(BigDecimal.ZERO); invented.setGuaranteeBaseRate(BigDecimal.ZERO);
        invented.setGuaranteeStepRate(BigDecimal.ZERO); invented.setGuaranteeMaxMerchant(0);
        assertThrows(IllegalStateException.class,()->fenced(22,()->mapper.recordClose(invented)));
        assertEquals(0,count(CLOSE)); assertEquals(0,count(EVENT)); assertLegacy();
    }
    @Test void eventStorageRejectsSelfDeclaredVerifiedPaymentEvenWithExistingRefundAndContribution() throws Exception {
        expand(); CoopSettlementCashClose c=evidence(11,101,"10.00");
        for (String type:List.of("PAYMENT_SUCCESS","VERIFIED_PAYMENT")) {
            CoopSettlementCashEvent unsupported=event(c.getId(),501,101,"5.00"); unsupported.setEventType(type);
            unsupported.setSourceSnapshot("{\"verified\":true,\"transactionId\":\"TEST-SELF-CLAIM\"}");
            assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordEvent(unsupported)));
        }
        assertEquals(0,count(EVENT)); assertEquals(1,count(CLOSE)); assertEquals(1,count(CONTRIBUTION)); assertLegacy();
    }

    @Test void expandPreservesLegacyOriginalRowsAndBothOldUniqueKeys() throws Exception {
        assertEquals(0,preflight()); expand();
        assertLegacy();
        assertEquals("NO",db.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE()"
                +" AND table_name='coop_settlement_adjustment' AND column_name='claim_id'",String.class));
        assertEquals(1,indexColumns("coop_settlement","uk_settle_order").size());
        assertEquals(List.of("claim_id","entry_kind","event_key"),indexColumns("coop_settlement_adjustment","uk_coop_adjustment_event"));
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO coop_settlement(topic_id,coop_order_id,merchant_id,amount) VALUES(99,700,88,7)"));
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO coop_settlement_adjustment(settlement_id,claim_id,entry_kind,event_key,amount,status,create_time)"
                +" VALUES(1,71,'ADJUSTMENT','legacy-original-event',-1,'EXECUTED',NOW())"));
        assertLegacy();
    }
    @Test void onlyExplicitAccountingPlatformAndDepositRowsMayHaveNoMember() throws Exception {
        expand();
        head(10,11,null,"ACCOUNTING","PLATFORM","platform",1,null);
        head(11,11,null,"ACCOUNTING","DEPOSIT_DEDUCTION","platform",1,null);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM coop_settlement WHERE merchant_id IS NULL AND cash_head_kind='ACCOUNTING'",Integer.class));
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO coop_settlement(topic_id,merchant_id,line_type,payee_type,status) VALUES(11,NULL,'PLATFORM','platform',1)"),
                "旧写侧未显式ACCOUNTING，仍默认MEMBER而拒绝");
        assertThrows(DataAccessException.class,()->head(12,11,null,"MEMBER","MERCHANT_VERIFY","merchant",0,null));
        assertThrows(DataAccessException.class,()->head(13,11,0L,"MEMBER","MERCHANT_VERIFY","merchant",0,null));
        assertThrows(DataAccessException.class,()->head(14,11,999L,"MEMBER","MERCHANT_VERIFY","merchant",0,null));
        assertThrows(DataAccessException.class,()->head(15,11,88L,"ACCOUNTING","PLATFORM","platform",1,null));
        assertEquals(3,count("coop_settlement"));
    }
    @Test void sqlNullCannotBypassAccountingKindRoleLineOrStatus() throws Exception {
        expand();
        assertThrows(DataAccessException.class,()->head(10,11,null,null,"PLATFORM","platform",1,null));
        assertThrows(DataAccessException.class,()->head(11,11,null,"ACCOUNTING",null,"platform",1,null));
        assertThrows(DataAccessException.class,()->head(12,11,null,"ACCOUNTING","PLATFORM",null,1,null));
        assertThrows(DataAccessException.class,()->head(13,11,null,"ACCOUNTING","PLATFORM","platform",null,null));
        assertThrows(DataAccessException.class,()->head(14,11,null,"ACCOUNTING","PLATFORM","platform",0,null));
        assertThrows(DataAccessException.class,()->head(15,11,null,"ACCOUNTING ","PLATFORM","platform",1,null));
        assertThrows(DataAccessException.class,()->head(16,11,null,"ACCOUNTING","platform","platform",1,null));
        assertEquals(1,count("coop_settlement"));
    }
    @Test void sourceTopicInitiatorAndJsonObjectMustActuallyMatch() throws Exception {
        expand();
        CoopSettlementCashClose wrong=close(11); wrong.setInitiatorMemberId(88L);
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordClose(wrong)));
        CoopSettlementCashClose jsonNull=close(11); jsonNull.setContractSnapshot("null");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(jsonNull)));
        CoopSettlementCashClose array=close(11); array.setContractSnapshot("[]");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordClose(array)));
        assertEquals(0,count(CLOSE)); assertLegacy();
    }
    @Test void parentOrderUsesOriginalPayerNotMinimumRegistrationId() throws Exception {
        expand();
        fenced(33,()->{
            CoopSettlementCashClose c=close(33); c.setOriginalContributionCount(2); c=mapper.recordClose(c);
            CoopSettlementCashContribution payer=contribution(c.getId(),302,33,"100.00");
            payer.setParentOrderNo("PARENT-302"); mapper.recordContribution(payer);
            CoopSettlementCashContribution child=contribution(c.getId(),301,33,"0.00");
            child.setParentOrderNo("PARENT-302"); child.setPayerRegistrationId(302L); child.setPayerOrderNo("G-REG-302");
            CoopSettlementCashContribution saved=mapper.recordContribution(child);
            assertEquals(302L,saved.getPayerRegistrationId());
            assertEquals(0,new BigDecimal("100.00").compareTo(db.queryForObject("SELECT SUM(sales_basis_amount) FROM "+CONTRIBUTION,BigDecimal.class)));
            return null;
        });
    }
    @Test void contributionCannotBorrowAnotherCloseTopicOrPayer() throws Exception {
        expand();
        CoopSettlementCashClose first=fenced(11,()->mapper.recordClose(close(11)));
        CoopSettlementCashClose second=fenced(22,()->mapper.recordClose(close(22)));
        fenced(22,()->mapper.recordContribution(contribution(second.getId(),201,22,"20.00")));
        CoopSettlementCashContribution wrong=contribution(second.getId(),101,11,"10.00");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordContribution(wrong)));
        CoopSettlementCashContribution payer=contribution(first.getId(),101,11,"10.00");
        payer.setPayerRegistrationId(201L); payer.setPayerOrderNo("G-REG-201");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(payer)));
        assertEquals(1,count(CONTRIBUTION));
    }
    @Test void eventCannotPointToRegistrationContributionInAnotherClose() throws Exception {
        expand();
        CoopSettlementCashClose first=evidence(11,101,"10.00");
        evidence(22,201,"20.00");
        CoopSettlementCashEvent event=event(first.getId(),502,201,"5.00");
        assertThrows(DataAccessException.class,()->fenced(11,()->mapper.recordEvent(event)));
        assertEquals(0,count(EVENT));
    }
    @Test void refundAndContributionExistingIndependentlyDoNotProveTheSameSourceRegistration() throws Exception {
        expand(); CoopSettlementCashClose first=evidence(11,101,"10.00");
        evidence(22,201,"20.00");
        // refund 502 exists, registration 101 is in first close; independent FKs alone cannot bind them.
        CoopSettlementCashEvent wrong=event(first.getId(),502,101,"5.00");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordEvent(wrong)));
        assertEquals(0,count(EVENT)); assertEquals(2,count(CONTRIBUTION)); assertLegacy();
        CoopSettlementCashEvent right=fenced(11,()->mapper.recordEvent(event(first.getId(),501,101,"5.00")));
        assertEquals(501L,right.getSourceId()); assertEquals(101L,right.getSourceRegistrationId());
        assertEquals(1,count(EVENT));
    }
    @Test void headAndAdjustmentCompositeForeignKeysRejectCrossCloseAndHalfLinks() throws Exception {
        expand();
        CoopSettlementCashClose first=evidence(11,101,"10.00");
        CoopSettlementCashClose second=evidence(22,201,"20.00");
        CoopSettlementCashEvent a=fenced(11,()->mapper.recordEvent(event(first.getId(),501,101,"5.00")));
        CoopSettlementCashEvent b=fenced(22,()->mapper.recordEvent(event(second.getId(),502,201,"5.00")));
        assertThrows(DataAccessException.class,()->head(9,22,88L,"MEMBER","MERCHANT_VERIFY","merchant",0,first.getId()));
        head(10,11,88L,"MEMBER","MERCHANT_VERIFY","merchant",0,first.getId());
        claimAndDetail(72,11,101,800,10,"1.00");
        assertThrows(DataAccessException.class,()->adjustment(10,72,first.getId(),b.getId(),"wrong-event"));
        assertThrows(DataAccessException.class,()->adjustment(10,72,second.getId(),b.getId(),"wrong-head"));
        assertThrows(DataAccessException.class,()->adjustment(10,72,null,a.getId(),"half-event"));
        assertThrows(DataAccessException.class,()->adjustment(10,72,first.getId(),null,"half-close"));
        adjustment(10,72,first.getId(),a.getId(),"valid-source");
        assertThrows(DataAccessException.class,()->adjustment(10,72,first.getId(),a.getId(),"same-event-different-key"));
        assertEquals(2,count("coop_settlement_adjustment"));
    }
    @Test void globalHeadIdentityAndInitiatorMustMatchFrozenClose() throws Exception {
        expand(); CoopSettlementCashClose c=evidence(11,101,"10.00");
        assertThrows(DataAccessException.class,()->head(10,11,88L,"MEMBER","INITIATOR_GUARANTEE","initiator",0,c.getId()));
        head(11,11,77L,"MEMBER","INITIATOR_GUARANTEE","initiator",0,c.getId());
        assertThrows(DataAccessException.class,()->head(12,11,77L,"MEMBER","INITIATOR_GUARANTEE","initiator",0,c.getId()));
        head(13,11,null,"ACCOUNTING","PLATFORM","platform",1,c.getId());
        assertThrows(DataAccessException.class,()->head(14,11,null,"ACCOUNTING","PLATFORM","platform",1,c.getId()));
        assertEquals(3,count("coop_settlement"));
    }
    @Test void sameCloseIdentityReadsOriginalAndDifferentPayloadCannotOverwrite() throws Exception {
        expand();
        CoopSettlementCashClose original=fenced(11,()->mapper.recordClose(close(11)));
        CoopSettlementCashClose same=close(11); same.setContractSnapshot("{ \"orders\":[], \"schemaVersion\":1 }");
        assertEquals(original.getId(),fenced(11,()->mapper.recordClose(same)).getId());
        CoopSettlementCashClose changed=close(11); changed.setOriginalSalesBasis(new BigDecimal("11.00"));
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordClose(changed)));
        assertEquals(1,count(CLOSE)); assertEquals(0,new BigDecimal("10.00").compareTo(db.queryForObject("SELECT original_sales_basis FROM "+CLOSE,BigDecimal.class)));
    }
    @Test void originalContributionReceiptSurvivesSourceRefundAndRejectsChangedAmount() throws Exception {
        expand(); CoopSettlementCashClose c=evidence(11,101,"10.00");
        CoopSettlementCashContribution before=fenced(11,()->mapper.selectContributionForUpdate(101L));
        db.update("UPDATE cms_registration SET payment_status=4,registration_status=3 WHERE id=101");
        CoopSettlementCashContribution same=contribution(c.getId(),101,11,"10.00");
        assertEquals(before.getId(),fenced(11,()->mapper.recordContribution(same)).getId());
        CoopSettlementCashContribution changed=contribution(c.getId(),101,11,"9.00");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(changed)));
        assertEquals(1,count(CONTRIBUTION));
        assertEquals(0,new BigDecimal("10.00").compareTo(db.queryForObject("SELECT sales_basis_amount FROM "+CONTRIBUTION,BigDecimal.class)));
    }
    @Test void repeatedEventWithSameHashStillConflictsOnDifferentActualPayload() throws Exception {
        expand(); CoopSettlementCashClose c=evidence(11,101,"10.00");
        CoopSettlementCashEvent saved=fenced(11,()->mapper.recordEvent(event(c.getId(),501,101,"5.00")));
        assertEquals(saved.getId(),fenced(11,()->mapper.recordEvent(event(c.getId(),501,101,"5.00"))).getId());
        CoopSettlementCashEvent differentJson=event(c.getId(),501,101,"5.00"); differentJson.setSourceSnapshot("{\"refundId\":999}");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordEvent(differentJson)));
        CoopSettlementCashEvent differentAmount=event(c.getId(),501,101,"6.00");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordEvent(differentAmount)));
        assertEquals(1,count(EVENT)); assertEquals(0,new BigDecimal("5.00").compareTo(db.queryForObject("SELECT source_amount FROM "+EVENT,BigDecimal.class)));
    }
    @Test void failureAfterEvidenceHeadAndAdjustmentRollsBackAllNewRows() throws Exception {
        expand();
        assertThrows(IllegalStateException.class,()->fenced(11,()->{
            CoopSettlementCashClose c=mapper.recordClose(close(11)); mapper.recordContribution(contribution(c.getId(),101,11,"10.00"));
            CoopSettlementCashEvent e=mapper.recordEvent(event(c.getId(),501,101,"5.00"));
            head(10,11,88L,"MEMBER","MERCHANT_VERIFY","merchant",0,c.getId());
            claimAndDetail(72,11,101,800,10,"1.00"); adjustment(10,72,c.getId(),e.getId(),"rolled-back");
            throw new IllegalStateException("injected before actual transaction commit");
        }));
        assertEquals(0,count(CLOSE)); assertEquals(0,count(CONTRIBUTION)); assertEquals(0,count(EVENT));
        assertEquals(1,count("coop_settlement")); assertEquals(1,count("coop_settlement_adjustment"));
        assertEquals(1,count("settlement_claim")); assertEquals(1,count("coop_settlement_detail"));
        assertLegacy(); assertEquals(0,new BigDecimal("50.00").compareTo(db.queryForObject("SELECT balance FROM ums_member WHERE id=77",BigDecimal.class)));
    }
    @Test void dirtyLegacyPreflightFailsBeforeAnyDdlAndDoesNotDeleteTheEvidence() {
        db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,amount) VALUES(2,11,9999,12)");
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns());
        assertEquals(2,count("coop_settlement")); assertEquals(0,new BigDecimal("12.00").compareTo(db.queryForObject("SELECT amount FROM coop_settlement WHERE id=2",BigDecimal.class)));
    }
    @Test void changedOriginalOrderUniqueKeyFailsPreflightBeforeAnyDdl() {
        db.execute("ALTER TABLE coop_settlement DROP INDEX uk_settle_order, ADD UNIQUE KEY uk_settle_order(coop_order_id,merchant_id)");
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
        assertEquals(List.of("coop_order_id","merchant_id"),indexColumns("coop_settlement","uk_settle_order"));
    }
    @Test void changedOriginalAdjustmentIdentityFailsPreflightEvenWithThreeUniqueColumns() {
        db.execute("ALTER TABLE coop_settlement_adjustment DROP INDEX uk_coop_adjustment_event, "
                +"ADD UNIQUE KEY uk_coop_adjustment_event(claim_id,entry_kind,settlement_id)");
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
        assertEquals(List.of("claim_id","entry_kind","settlement_id"),indexColumns("coop_settlement_adjustment","uk_coop_adjustment_event"));
    }
    @Test void existingPartialSchemaIsNotOverwrittenOrTreatedAsApplied() {
        db.execute("CREATE TABLE coop_settlement_cash_event(id BIGINT PRIMARY KEY)"); // deliberate wrong-shape negative control, own UUID only.
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(1,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
    }
    @Test void emptyOnlyRollbackRestoresOriginalColumnsAndIndexShape() throws Exception {
        List<Map<String,Object>> columns=legacyColumns(); List<Map<String,Object>> indexes=legacyIndexes();
        expand(); run("_deploy_g_cash_evidence_empty_rollback_20261023.sql");
        assertEquals(columns,legacyColumns()); assertEquals(indexes,legacyIndexes());
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
    }
    @Test void rollbackRefusesAnyCommittedEvidenceInsteadOfDroppingMoneyHistory() throws Exception {
        expand(); evidence(11,101,"10.00");
        assertThrows(DataAccessException.class,()->run("_deploy_g_cash_evidence_empty_rollback_20261023.sql"));
        assertEquals(3,newTables()); assertEquals(1,count(CLOSE)); assertEquals(1,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void rollbackAlsoRefusesAccountingRowsEvenWithoutCashClose() throws Exception {
        expand(); head(10,11,null,"ACCOUNTING","PLATFORM","platform",1,null);
        assertThrows(DataAccessException.class,()->run("_deploy_g_cash_evidence_empty_rollback_20261023.sql"));
        assertEquals(3,newTables()); assertEquals(2,count("coop_settlement"));
        assertNull(db.queryForObject("SELECT merchant_id FROM coop_settlement WHERE id=10",Long.class));
    }
    @Test void defaultRecordRequiresRealTransactionAndOriginalSourceScope() throws Exception {
        expand();
        assertThrows(IllegalStateException.class,()->mapper.recordClose(close(11)));
        CoopSettlementCashClose c=fenced(11,()->mapper.recordClose(close(11)));
        CoopSettlementCashContribution wrong=contribution(c.getId(),101,11,"11.00");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(wrong)));
        CoopSettlementCashContribution name=contribution(c.getId(),101,11,"10.00"); name.setRegistrationNo("g-reg-101");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(name)));
        assertEquals(0,count(CONTRIBUTION)); assertEquals(0,count(EVENT));
    }


    @Test void independentOrdersWithNullParentCannotBorrowEachOthersPaymentIdentity() throws Exception {
        expand();
        CoopSettlementCashClose request=close(11); request.setOriginalContributionCount(2);
        CoopSettlementCashClose c=fenced(11,()->mapper.recordClose(request));
        CoopSettlementCashContribution waiting=contribution(c.getId(),102,11,"0.00");
        waiting.setSnapshotPaymentStatus(0); waiting.setSnapshotRegistrationStatus(1);
        waiting.setSnapshotPayableAmount(new BigDecimal("90.00"));
        waiting.setRegistrationSnapshot("{\"paymentStatus\":0,\"registrationStatus\":1,\"verificationStatus\":0}");
        fenced(11,()->mapper.recordContribution(waiting));
        CoopSettlementCashContribution wrong=contribution(c.getId(),101,11,"10.00");
        wrong.setPayerRegistrationId(102L); wrong.setPayerOrderNo("G-REG-102");
        assertThrows(IllegalStateException.class,()->fenced(11,()->mapper.recordContribution(wrong)),
                "NULL parent is absence of a payment group, not proof that two independent orders share a payer");
        assertEquals(1,count(CONTRIBUTION));
        assertNull(fenced(11,()->mapper.selectContributionForUpdate(101L))); assertLegacy();
    }
    @Test void zeroPayableSiblingCannotSelfDeclareAsTheParentPaymentIdentity() throws Exception {
        expand();
        CoopSettlementCashClose request=close(33); request.setOriginalContributionCount(2);
        CoopSettlementCashClose saved=fenced(33,()->mapper.recordClose(request));
        CoopSettlementCashContribution child=contribution(saved.getId(),301,33,"0.00");
        child.setParentOrderNo("PARENT-302");
        assertThrows(IllegalStateException.class,()->fenced(33,()->mapper.recordContribution(child)),
                "fixture302 is the original100 payment subject; sibling301 is not made payer by caller choice");
        assertEquals(0,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void reservedAdjustmentIndexNameCollisionMustBeRejectedBeforeAnyDdl() {
        db.execute("ALTER TABLE coop_settlement_adjustment ADD UNIQUE KEY uk_g_cash_adjustment_event(id)");
        assertEquals(1,preflight(),"existing planned index name is a migration blocker even without new columns");
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
        assertEquals(List.of("id"),indexColumns("coop_settlement_adjustment","uk_g_cash_adjustment_event"));
    }
    @Test void interruptedEmptyMigrationNeedsSafeRollbackBeforeAdjustmentAlterExists() throws Exception {
        List<Map<String,Object>> columns=legacyColumns(); List<Map<String,Object>> indexes=legacyIndexes();
        assertEquals(0,preflight());
        String text=Files.readString(sql.resolve("migration_20261023_g_cash_evidence_expand.sql"))
                .replaceAll("(?m)^\\s*--.*$","");
        int executed=0;
        for (String part:text.split(";")) {
            String statement=part.trim(); if (statement.isEmpty()) continue;
            if (statement.startsWith("ALTER TABLE coop_settlement_adjustment")) break;
            db.execute(statement); executed++;
        }
        assertEquals(5,executed); // preflight SELECT, three CREATEs, first ALTER each actually auto-committed
        assertEquals(3,newTables()); assertEquals(1,newHeadColumns());
        assertEquals(0,count(CLOSE)); assertEquals(0,count(CONTRIBUTION)); assertEquals(0,count(EVENT));
        assertDoesNotThrow(()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent()),
                "empty partial apply must have a reviewed recovery route; no money/evidence row may be removed");
        assertEquals(0,newTables()); assertEquals(columns,legacyColumns()); assertEquals(indexes,legacyIndexes()); assertLegacy();
    }

    @Test void parentWithOnePositiveOriginalPayerStillRecordsAndReplaysBothRows() throws Exception {
        expand();
        CoopSettlementCashClose c=fenced(33,()->mapper.recordClose(close(33)));
        CoopSettlementCashContribution payer=contribution(c.getId(),302,33,"100.00"); payer.setParentOrderNo("PARENT-302");
        CoopSettlementCashContribution saved=fenced(33,()->mapper.recordContribution(payer));
        assertEquals(saved.getId(),fenced(33,()->mapper.recordContribution(payer)).getId());
        CoopSettlementCashContribution child=contribution(c.getId(),301,33,"0.00"); child.setParentOrderNo("PARENT-302");
        child.setPayerRegistrationId(302L); child.setPayerOrderNo("G-REG-302");
        assertEquals(302L,fenced(33,()->mapper.recordContribution(child)).getPayerRegistrationId());
        assertEquals(2,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void parentWithAllZeroAmountsHasNoProvenPaymentSubject() throws Exception {
        expand(); db.update("UPDATE cms_registration SET payable_amount=0 WHERE id=302");
        CoopSettlementCashClose c=fenced(33,()->mapper.recordClose(zeroClose(33,2)));
        CoopSettlementCashContribution unknown=contribution(c.getId(),302,33,"0.00"); unknown.setParentOrderNo("PARENT-302");
        IllegalStateException rejected=assertThrows(IllegalStateException.class,()->fenced(33,()->mapper.recordContribution(unknown)));
        assertEquals("CASH_EVIDENCE_PAYER_OR_SOURCE_IDENTITY_UNPROVEN",rejected.getMessage());
        assertEquals(0,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void parentWithTwoPositiveOrMissingSiblingAmountsCannotGuessItsPayer() throws Exception {
        expand(); CoopSettlementCashClose c=fenced(33,()->mapper.recordClose(close(33)));
        CoopSettlementCashContribution payer=contribution(c.getId(),302,33,"100.00"); payer.setParentOrderNo("PARENT-302");
        db.update("UPDATE cms_registration SET payable_amount=1 WHERE id=301");
        assertThrows(IllegalStateException.class,()->fenced(33,()->mapper.recordContribution(payer)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->db.update("UPDATE cms_registration SET payable_amount=NULL WHERE id=301"));
        assertEquals(0,db.queryForObject("SELECT payable_amount FROM cms_registration WHERE id=301",BigDecimal.class).compareTo(BigDecimal.ONE));
        assertThrows(IllegalStateException.class,()->fenced(33,()->mapper.recordContribution(payer)));
        assertEquals(0,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void nullParentCannotBorrowPayerThroughDirectInsertEither() throws Exception {
        expand(); CoopSettlementCashClose c=evidence(11,101,"10.00");
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO "+CONTRIBUTION
                +"(cash_close_id,registration_id,payer_registration_id,parent_order_no,registration_no,payer_order_no,owner_type,owner_id,source_topic_id,"
                +"snapshot_payment_status,snapshot_registration_status,snapshot_verification_status,snapshot_payable_amount,sales_basis_amount,registration_snapshot,source_fingerprint)"
                +" VALUES(?,102,101,NULL,'G-REG-102','G-REG-101',1,11,11,0,1,0,90,0,'{}',?)",c.getId(),HASH));
        assertEquals(1,count(CONTRIBUTION)); assertLegacy();
    }
    @Test void everyPlannedLegacyTableIndexNameIsABeforeDdlBlocker() {
        Map<String,List<String>> planned=new LinkedHashMap<>();
        planned.put("coop_settlement",List.of("idx_g_cash_head_member","idx_g_cash_head_close","idx_g_cash_head_initiator","uk_g_cash_head_id_close","uk_g_cash_global_head"));
        planned.put("coop_settlement_adjustment",List.of("idx_g_cash_adjustment_event","idx_g_cash_adjustment_head","uk_g_cash_adjustment_event"));
        planned.forEach((table,names)->names.forEach(name->{
            db.execute("ALTER TABLE "+table+" ADD KEY "+name+"(id)");
            assertEquals(1,preflight(),name);
            assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
            assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertEquals(List.of("id"),indexColumns(table,name)); assertLegacy();
            db.execute("ALTER TABLE "+table+" DROP INDEX "+name); // only the fixture object created immediately above.
        }));
        assertEquals(0,preflight());
    }
    @Test void plannedCheckAndForeignKeySymbolsAreBeforeDdlBlockers() {
        db.execute("ALTER TABLE cms_topic ADD CONSTRAINT ck_g_cash_close CHECK(member_id>0)");
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
        db.execute("ALTER TABLE cms_topic DROP CHECK ck_g_cash_close");
        db.execute("ALTER TABLE cms_topic ADD recovery_fixture_member BIGINT UNSIGNED NULL, "
                +"ADD CONSTRAINT fk_g_cash_close_member FOREIGN KEY(recovery_fixture_member) REFERENCES ums_member(id)");
        assertEquals(1,preflight());
        assertThrows(DataAccessException.class,()->run("migration_20261023_g_cash_evidence_expand.sql"));
        assertEquals(0,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
    }
    @Test void everyEmptyCommittedDdlPrefixCanRecoverAndForwardApplyAgain() throws Exception {
        List<Map<String,Object>> columns=legacyColumns(); List<Map<String,Object>> indexes=legacyIndexes();
        for (int stage=1;stage<=5;stage++) {
            applyCommittedPrefix(stage);
            GCashEvidenceEmptyRecovery.Result result=GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent());
            assertEquals(stage,result.stage());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,result.referenceSchema()));
            assertEquals(0,newTables()); assertEquals(columns,legacyColumns()); assertEquals(indexes,legacyIndexes()); assertLegacy();
            assertEquals(0,preflight());
        }
        expand(); assertEquals(3,newTables()); assertLegacy();
    }
    @Test void recoveryRefusesNonemptyCommittedClosePrefix() throws Exception {
        applyCommittedPrefix(1); bindExistingMapper();
        fenced(11,()->mapper.recordClose(close(11)));
        assertThrows(IllegalStateException.class,()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent()));
        assertEquals(1,newTables()); assertEquals(1,count(CLOSE)); assertEquals(0,newHeadColumns()); assertLegacy();
    }
    @Test void recoveryRefusesUnknownColumnsAndChangedCheckEvenWhenEmpty() throws Exception {
        applyCommittedPrefix(3);
        db.execute("ALTER TABLE "+CLOSE+" ADD unknown_owner_fact INT NULL");
        assertThrows(IllegalStateException.class,()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent()));
        assertEquals(3,newTables()); assertLegacy();
        db.execute("ALTER TABLE "+CLOSE+" DROP COLUMN unknown_owner_fact"); // only own test column.
        db.execute("ALTER TABLE "+CLOSE+" DROP CHECK ck_g_cash_close, ADD CONSTRAINT ck_g_cash_close CHECK(topic_id>0)");
        assertThrows(IllegalStateException.class,()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent()));
        assertEquals(3,newTables()); assertEquals(0,count(CLOSE)); assertLegacy();
    }
    @Test void recoveryRefusesAccountingHeadAtCommittedHeadStage() throws Exception {
        applyCommittedPrefix(4); head(10,11,null,"ACCOUNTING","PLATFORM","platform",1,null);
        assertThrows(IllegalStateException.class,()->GCashEvidenceEmptyRecovery.recoverEmpty(source,sql.toAbsolutePath().normalize().getParent().getParent()));
        assertEquals(3,newTables()); assertEquals(1,newHeadColumns()); assertEquals(2,count("coop_settlement")); assertLegacy();
    }
    @Test void fullOnlyRollbackRefusesPrefixBeforeReferencingMissingColumns() throws Exception {
        applyCommittedPrefix(2);
        assertThrows(DataAccessException.class,()->run("_deploy_g_cash_evidence_empty_rollback_20261023.sql"));
        assertEquals(2,newTables()); assertEquals(0,newHeadColumns()); assertLegacy();
    }

    private void applyCommittedPrefix(int ddlCount) throws Exception {
        String text=Files.readString(sql.resolve("migration_20261023_g_cash_evidence_expand.sql")).replaceAll("(?m)^\\s*--.*$","");
        List<String> statements=Arrays.stream(text.split(";")).map(String::trim).filter(v->!v.isEmpty()).toList();
        assertEquals(7,statements.size());
        db.execute(statements.get(0));
        for (int i=1;i<=ddlCount;i++) db.execute(statements.get(i));
        assertEquals(Math.min(ddlCount,3),newTables());
    }
    private void bindExistingMapper() throws Exception {
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementCashEvidenceMapper.xml"));
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CoopSettlementCashEvidenceMapper.class);
    }

    private void expand() throws Exception {
        run("migration_20261023_g_cash_evidence_expand.sql");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopSettlementCashEvidenceMapper.xml"));
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CoopSettlementCashEvidenceMapper.class);
    }
    private void run(String name) { new ResourceDatabasePopulator(new FileSystemResource(sql.resolve(name))).execute(source); }
    private int preflight() {
        try { return db.queryForObject(Files.readString(sql.resolve("_precheck_g_cash_evidence_20261023.sql")),Integer.class); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private <T> T fenced(long topicId,Supplier<T> body) {
        return tx.execute(status->{
            assertEquals(topicId,db.queryForObject("SELECT id FROM cms_topic WHERE id=? FOR UPDATE",Long.class,topicId));
            return body.get();
        });
    }
    private CoopSettlementCashClose evidence(long topic,int registration,String amount) {
        return fenced(topic,()->{
            CoopSettlementCashClose c=mapper.recordClose(close(topic));
            mapper.recordContribution(contribution(c.getId(),registration,topic,amount)); return c;
        });
    }
    private CoopSettlementCashClose close(long topic) {
        CoopSettlementCashClose row=new CoopSettlementCashClose(); row.setTopicId(topic); row.setCommercialContextType("TOPIC_EDITION");
        row.setCommercialContextId(topic); row.setCloseKind("POSITIVE_SALES"); row.setInitiatorMemberId(77L);
        row.setOriginalSalesBasis(new BigDecimal(topic==22?"20.00":topic==33?"100.00":"10.00")); row.setOriginalContributionCount(1);
        row.setPlatformRate(new BigDecimal("0.10")); row.setGuaranteeBaseRate(new BigDecimal("0.05")); row.setGuaranteeStepRate(new BigDecimal("0.02"));
        row.setGuaranteeMaxMerchant(5); row.setOriginalVerifiedMerchantCount(1); row.setOriginCalculationVersion("CLASSIC_TOPIC_ORIGIN_V1");
        row.setContractSnapshot("{\"schemaVersion\":1,\"orders\":[]}"); row.setSourceFingerprint(HASH); row.setClosedAt(new java.util.Date(1700000000123L)); return row;
    }
    private CoopSettlementCashClose zeroClose(long topic,int observedRows) {
        CoopSettlementCashClose row=close(topic); row.setCloseKind("ZERO_SALES"); row.setOriginalSalesBasis(BigDecimal.ZERO);
        row.setOriginalContributionCount(observedRows); row.setOriginalVerifiedMerchantCount(0); return row;
    }
    private CoopSettlementCashContribution contribution(Long close,int registration,long topic,String amount) {
        CoopSettlementCashContribution row=new CoopSettlementCashContribution(); row.setCashCloseId(close); row.setRegistrationId((long)registration);
        row.setPayerRegistrationId((long)registration); row.setRegistrationNo("G-REG-"+registration); row.setPayerOrderNo("G-REG-"+registration);
        row.setOwnerType(1); row.setOwnerId(topic); row.setSourceTopicId(topic);
        row.setSnapshotPaymentStatus(2); row.setSnapshotRegistrationStatus(2); row.setSnapshotVerificationStatus(0);
        row.setSnapshotPayableAmount(new BigDecimal(amount));
        row.setSalesBasisAmount(new BigDecimal(amount)); row.setRegistrationSnapshot("{\"paymentStatus\":2,\"registrationStatus\":2,\"verificationStatus\":0}");
        row.setSourceFingerprint(HASH); return row;
    }
    private CoopSettlementCashEvent event(Long close,long source,int registration,String amount) {
        CoopSettlementCashEvent row=new CoopSettlementCashEvent(); row.setCashCloseId(close); row.setEventType("REFUND_APPROVED");
        row.setSourceId(source); row.setSourceRegistrationId((long)registration); row.setSourceAmount(new BigDecimal(amount));
        row.setSourceSnapshot("{\"refundId\":"+source+"}"); row.setSourceFingerprint(HASH); return row;
    }
    private void registration(int id,long topic,String amount,String parent,int payment) {
        db.update("INSERT INTO cms_registration(id,registration_no,parent_order_no,owner_type,owner_id,ticket_id,ticket_price,member_id,order_num,"
                +"original_amount,total_amount,payable_amount,payment_status,registration_status,verification_status,purchase_kind,pay_expire_time)"
                +" VALUES(?,?,?,1,?,21,?,101,1,?,?,?, ?,?,0,1,'2026-10-01 08:00:00')",
                id,"G-REG-"+id,parent,topic,amount,amount,amount,amount,payment,payment==2?2:1);
    }
    private void head(long id,long topic,Long member,String kind,String line,String payee,Integer status,Long close) {
        db.update("INSERT INTO coop_settlement(id,topic_id,merchant_id,cash_head_kind,line_type,payee_type,status,amount,cash_close_id) VALUES(?,?,?,?,?,?,?,1,?)",
                id,topic,member,kind,line,payee,status,close);
    }
    private void claimAndDetail(long claim,long topic,int registration,long order,long head,String amount) {
        db.update("INSERT INTO settlement_claim(id,commercial_context_type,commercial_context_id,fulfillment_type,fulfillment_id,beneficiary_role,beneficiary_id,"
                +"service_unit,settlement_route,source_contract_type,source_contract_id,status,create_time)"
                +" VALUES(?,'TOPIC_EDITION',?,'REGISTRATION',?,'MERCHANT',88,'MERCHANT_PARTICIPATION','COOP_ORDER','COOP_ORDER',?,'ACTIVE',NOW())",
                claim,topic,registration,order);
        db.update("INSERT INTO coop_settlement_detail(settlement_id,claim_id,registration_id,origin_amount,create_time) VALUES(?,?,?,?,NOW())",head,claim,registration,amount);
    }
    private void adjustment(long head,long claim,Long close,Long event,String key) {
        db.update("INSERT INTO coop_settlement_adjustment(settlement_id,claim_id,entry_kind,event_key,amount,status,create_time,cash_close_id,cash_event_id)"
                +" VALUES(?,?,'ADJUSTMENT',?,-0.10,'EXECUTED',NOW(),?,?)",head,claim,key,close,event);
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private int newTables() { return db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (?,?,?)",Integer.class,CLOSE,CONTRIBUTION,EVENT); }
    private int newHeadColumns() { return db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' AND column_name='cash_close_id'",Integer.class); }
    private List<String> indexColumns(String table,String index) { return db.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index",String.class,table,index); }
    private void assertLegacy() {
        assertEquals(0,new BigDecimal("7.00").compareTo(db.queryForObject("SELECT amount FROM coop_settlement WHERE id=1",BigDecimal.class)));
        assertEquals(0,new BigDecimal("-1.00").compareTo(db.queryForObject("SELECT amount FROM coop_settlement_adjustment WHERE id=1",BigDecimal.class)));
        assertEquals(700L,db.queryForObject("SELECT coop_order_id FROM coop_settlement WHERE id=1",Long.class));
        assertEquals(71L,db.queryForObject("SELECT claim_id FROM coop_settlement_adjustment WHERE id=1",Long.class));
    }
    private List<Map<String,Object>> legacyColumns() { return db.queryForList("SELECT table_name,column_name,column_type,is_nullable,column_default,extra,generation_expression,column_comment FROM information_schema.columns"
            +" WHERE table_schema=DATABASE() AND table_name IN ('coop_settlement','coop_settlement_adjustment') ORDER BY table_name,ordinal_position"); }
    private List<Map<String,Object>> legacyIndexes() { return db.queryForList("SELECT table_name,index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics"
            +" WHERE table_schema=DATABASE() AND table_name IN ('coop_settlement','coop_settlement_adjustment') ORDER BY table_name,index_name,seq_in_index"); }
}
