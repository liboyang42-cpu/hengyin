package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real production mapper SQL and new migration, with a Spring-managed rollback boundary. No provider/network. */
class WxBillIngestionPersistenceTest {
    private PooledDataSource dataSource;
    private JdbcTemplate db;
    private TransactionTemplate transaction;
    private WxTradeBillDetailMapper details;
    private WxFundFlowBillSnapshotMapper funds;
    private WxBillImportMapper imports;
    private WxTradeBillSnapshotMapper legacy;
    private MysqlIntegrationTestConfig mysql;
    private boolean ownMysqlSchemaCreated;

    @BeforeEach void setup() throws Exception {
        boolean realMysql = "1".equals(System.getenv("CHENGYIN_FUNDS_MYSQL_IT"));
        if (realMysql) {
            // Match funds gate requirements; never silently connect using default/absent credentials.
            for (String name : new String[]{"CHENGYIN_TEST_MYSQL_HOST","CHENGYIN_TEST_MYSQL_PORT","CHENGYIN_TEST_MYSQL_USER"}) {
                assertNotNull(System.getenv(name),"Real MySQL mode missing required environment: "+name);
                assertFalse(System.getenv(name).isBlank(),"Real MySQL mode empty required environment: "+name);
            }
            assertTrue(System.getenv().containsKey("CHENGYIN_TEST_MYSQL_PASSWORD"),"Real MySQL mode password environment must be defined");
            mysql = MysqlIntegrationTestConfig.fromEnvironment("wx_bill_"+UUID.randomUUID().toString().replace("-","").substring(0,8));
            try (Connection admin=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password());
                 Statement statement=admin.createStatement()) {
                assertEquals("MySQL",admin.getMetaData().getDatabaseProductName());
                assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.getMetaData().getDatabaseProductVersion()));
                statement.execute("create database "+mysql.databaseName()+" character set utf8mb4");
                ownMysqlSchemaCreated=true;
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Required real MySQL unavailable; SQLState="+unavailable.getSQLState()+", errorCode="+unavailable.getErrorCode());
            }
            dataSource=new PooledDataSource("com.mysql.cj.jdbc.Driver",mysql.databaseUrl(),mysql.user(),mysql.password());
        } else {
            dataSource = new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:wx_bill_"+System.nanoTime()
                    +";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        }
        db = new JdbcTemplate(dataSource);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("chengyinhub-admin/sql"))) root = root.getParent();
        assertNotNull(root);
        Path migration=root.resolve("chengyinhub-admin/sql/migration_20261015_wx_bill_ingestion.sql");
        if (realMysql) {
            try (Connection connection=dataSource.getConnection()) {
                assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(migration));
            }
            assertEquals("utf8mb4_0900_bin",db.queryForObject("select table_collation from information_schema.tables where table_schema=database() and table_name='wx_trade_bill_detail'",String.class));
            assertEquals(3,db.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name in ('wx_trade_bill_detail','wx_fund_flow_bill_snapshot','wx_bill_import') and engine='InnoDB'",Integer.class));
            System.out.println("[WxBillIngestionPersistenceTest] databaseProduct=MySQL originalMigrationExecuted=true expectedCases=6");
        } else {
            String ddl=Files.readString(migration).replaceAll("(?m)^--.*$", "")
                    .replaceAll("(?i)\\) ENGINE=InnoDB[^;]*;", ");");
            for (String statement : ddl.split(";")) if (!statement.isBlank()) db.execute(statement);
            System.out.println("[WxBillIngestionPersistenceTest] databaseProduct=H2 originalMysqlMigrationExecuted=false expectedCases=6");
        }
        // Existing reconciliation table is a separate contract; the new migration never rewrites it.
        db.execute("create table wx_trade_bill_snapshot(id bigint auto_increment primary key,bill_date date not null,"
                +"wx_trade_no varchar(64) not null,out_trade_no varchar(64),out_refund_no varchar(64),biz_type int not null,"
                +"amount decimal(12,2),trade_time timestamp,raw_ingested_at timestamp default current_timestamp,"
                +"unique(wx_trade_no,biz_type))");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test",new SpringManagedTransactionFactory(),dataSource));
        config.getTypeAliasRegistry().registerAlias("WxTradeBillSnapshot",WxTradeBillSnapshot.class);
        for (String name : new String[]{"WxTradeBillDetail","WxFundFlowBillSnapshot","WxBillImport","WxTradeBillSnapshot"}) {
            String resource = "mapper/business/"+name+"Mapper.xml";
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sessions = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        details = sessions.getMapper(WxTradeBillDetailMapper.class);
        funds = sessions.getMapper(WxFundFlowBillSnapshotMapper.class);
        imports = sessions.getMapper(WxBillImportMapper.class);
        legacy = sessions.getMapper(WxTradeBillSnapshotMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach void close() throws Exception {
        if (dataSource != null) dataSource.forceCloseAll();
        if (ownMysqlSchemaCreated) {
            try (Connection admin=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password());
                 Statement statement=admin.createStatement()) {
                statement.execute("drop database "+mysql.databaseName());
            } catch (SQLException unavailable) {
                throw new IllegalStateException("Owned real MySQL schema cleanup failed; SQLState="+unavailable.getSQLState()+", errorCode="+unavailable.getErrorCode());
            }
        }
    }

    @Test void tradeIdentityIsScopedAndEveryOriginalFactMustMatchForIdempotence() {
        WxTradeBillDetail row = trade();
        assertEquals(1,details.insertDetail(row));
        assertEquals(1,details.countMatchingDetail(row));
        assertThrows(DuplicateKeyException.class,()->details.insertDetail(row));
        for (String field : new String[]{"billDate","merchantId","recordKey","tradeTime","transactionId",
                "outTradeNo","tradeState","refundId","outRefundNo","refundStatus","orderAmount",
                "settlementAmount","couponAmount","requestedRefundAmount","settlementRefundAmount","couponRefundAmount","fee"}) {
            WxTradeBillDetail changed = new WxTradeBillDetail(); BeanUtils.copyProperties(row,changed);
            alter(changed,field);
            assertEquals(0,details.countMatchingDetail(changed),field);
        }
        WxTradeBillDetail differentBytes=trade();differentBytes.setOutTradeNo("order-1 ");
        assertEquals(0,details.countMatchingDetail(differentBytes),"trailing bytes are not the same external fact");
        differentBytes.setOutTradeNo("ORDER-1");
        assertEquals(0,details.countMatchingDetail(differentBytes),"case is not normalized away");
        assertMoney("0.10",db.queryForObject("select order_amount from wx_trade_bill_detail",BigDecimal.class));
        assertMoney("-0.01",db.queryForObject("select fee from wx_trade_bill_detail",BigDecimal.class));
        WxTradeBillDetail otherDay=trade();otherDay.setBillDate(Date.valueOf("2026-10-15"));
        assertEquals(1,details.insertDetail(otherDay));
        WxTradeBillDetail otherMerchant=trade();otherMerchant.setMerchantId("merchant-B");
        assertEquals(1,details.insertDetail(otherMerchant));
        assertEquals(3,count("wx_trade_bill_detail"));assertEquals(0,count("wx_trade_bill_snapshot"));
    }

    @Test void refundFactsStaySeparateFromSuccessfulPaymentAndKeepRefundAmounts() {
        details.insertDetail(trade());
        WxTradeBillDetail refund=trade();refund.setRecordKey("REFUND:refund-1");refund.setTradeState("REFUND");
        refund.setRefundId("refund-1");refund.setOutRefundNo("refund-request-1");refund.setRefundStatus("PROCESSING");
        refund.setRequestedRefundAmount(new BigDecimal("0.08"));refund.setSettlementRefundAmount(new BigDecimal("0.06"));
        refund.setCouponRefundAmount(new BigDecimal("0.02"));
        assertEquals(1,details.insertDetail(refund));assertEquals(1,details.countMatchingDetail(refund));
        assertMoney("0.08",db.queryForObject("select requested_refund_amount from wx_trade_bill_detail where record_key='REFUND:refund-1'",BigDecimal.class));
        assertMoney("0.06",db.queryForObject("select settlement_refund_amount from wx_trade_bill_detail where record_key='REFUND:refund-1'",BigDecimal.class));
        assertEquals(0,count("wx_trade_bill_snapshot"),"pending refund raw fact must not manufacture successful reconciliation rows");
    }

    @Test void fundFlowIdentityIncludesMerchantAccountAndRejectsChangedCrossDayFacts() {
        WxFundFlowBillSnapshot row=fund();funds.insertSnapshot(row);
        assertEquals(1,funds.countMatchingSnapshot(row));
        for (String field : new String[]{"billDate","merchantId","accountType","accountingTime","wxBusinessNo",
                "flowNo","businessName","businessType","direction","amount","balance","initiator","remark","voucherNo"}) {
            WxFundFlowBillSnapshot changed=new WxFundFlowBillSnapshot();BeanUtils.copyProperties(row,changed);alter(changed,field);
            assertEquals(0,funds.countMatchingSnapshot(changed),field);
        }
        WxFundFlowBillSnapshot otherDay=fund();otherDay.setBillDate(Date.valueOf("2026-10-15"));
        assertThrows(DuplicateKeyException.class,()->funds.insertSnapshot(otherDay));
        WxFundFlowBillSnapshot otherAccount=fund();otherAccount.setAccountType("OPERATION");funds.insertSnapshot(otherAccount);
        WxFundFlowBillSnapshot otherMerchant=fund();otherMerchant.setMerchantId("merchant-B");funds.insertSnapshot(otherMerchant);
        assertEquals(3,count("wx_fund_flow_bill_snapshot"));assertEquals(0,count("wx_trade_bill_snapshot"));
        assertMoney("-1.10",db.queryForObject("select balance from wx_fund_flow_bill_snapshot where merchant_id='merchant-A' and account_type='BASIC'",BigDecimal.class));
    }

    @Test void successfulImportCannotBeDowngradedOrClearedByTemporaryMissingStatements() {
        WxBillImport missing=marker("NO_STATEMENT");imports.upsertImport(missing);
        assertEquals("NO_STATEMENT",imports.selectImport("merchant-A","BASIC",missing.getBillDate()).getStatus());
        WxBillImport success=marker("SUCCESS");success.setSourceHash("a".repeat(64));success.setRowCount(2);
        success.setIncomeAmount(new BigDecimal("0.10"));success.setExpenseAmount(new BigDecimal("0.20"));
        success.setNetAmount(new BigDecimal("-0.10"));success.setBalanceStatus("GAP");success.setReviewReason("explicit source gap");
        imports.upsertImport(success);
        WxBillImport upgraded=imports.selectImport("merchant-A","BASIC",success.getBillDate());
        assertEquals("SUCCESS",upgraded.getStatus());assertEquals("a".repeat(64),upgraded.getSourceHash());
        assertEquals(2,upgraded.getRowCount());assertMoney("0.10",upgraded.getIncomeAmount());
        imports.upsertImport(success);
        assertEquals(1,count("wx_bill_import"),"same-hash SUCCESS replay keeps one import identity");
        WxBillImport revised=marker("SUCCESS");revised.setSourceHash("d".repeat(64));revised.setRowCount(9);
        revised.setIncomeAmount(new BigDecimal("99.99"));revised.setBalanceStatus("CONSISTENT");
        imports.upsertImport(revised);
        assertEquals("a".repeat(64),imports.selectImport("merchant-A","BASIC",success.getBillDate()).getSourceHash());
        for (String status : new String[]{"NO_STATEMENT","CREATING"}) {
            imports.upsertImport(marker(status));
            WxBillImport read=imports.selectImport("merchant-A","BASIC",success.getBillDate());
            assertEquals("SUCCESS",read.getStatus());assertEquals("a".repeat(64),read.getSourceHash());
            assertEquals(2,read.getRowCount());assertEquals("GAP",read.getBalanceStatus());
            assertEquals("explicit source gap",read.getReviewReason());
            assertMoney("0.10",read.getIncomeAmount());assertMoney("0.20",read.getExpenseAmount());assertMoney("-0.10",read.getNetAmount());
        }
        WxBillImport anotherAccount=marker("CREATING");anotherAccount.setBillType("FEES");imports.upsertImport(anotherAccount);
        assertEquals("CREATING",imports.selectImport("merchant-A","FEES",success.getBillDate()).getStatus());
        assertNull(imports.selectImport("merchant-B","BASIC",success.getBillDate()));
        assertEquals(2,count("wx_bill_import"));
    }

    @Test void detailFundImportAndLegacySuccessRowsRollBackTogetherOnConflict() {
        legacy.insertSnapshot(legacy());
        assertThrows(DuplicateKeyException.class,()->transaction.execute(status->{
            details.insertDetail(trade());funds.insertSnapshot(fund());
            WxBillImport marker=marker("SUCCESS");marker.setSourceHash("b".repeat(64));marker.setRowCount(2);
            imports.upsertImport(marker);
            legacy.insertSnapshot(legacy());
            return null;
        }));
        assertEquals(0,count("wx_trade_bill_detail"));assertEquals(0,count("wx_fund_flow_bill_snapshot"));
        assertEquals(0,count("wx_bill_import"));assertEquals(1,count("wx_trade_bill_snapshot"));
        assertMoney("0.10",legacy.sumAmountByOutTradeNoAndBizType("order-1",1));
        WxBillImport accepted=marker("SUCCESS");accepted.setSourceHash("a".repeat(64));imports.upsertImport(accepted);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{
            details.insertDetail(trade());funds.insertSnapshot(fund());
            WxBillImport revised=marker("SUCCESS");revised.setSourceHash("b".repeat(64));revised.setRowCount(2);
            imports.upsertImport(revised);
            WxBillImport authoritative=imports.selectImport("merchant-A","BASIC",revised.getBillDate());
            assertEquals("a".repeat(64),authoritative.getSourceHash());
            if (!revised.getSourceHash().equals(authoritative.getSourceHash())) throw new IllegalStateException("Conflicting bill revision; all new rows must roll back");
            return null;
        }));
        assertEquals(0,count("wx_trade_bill_detail"));assertEquals(0,count("wx_fund_flow_bill_snapshot"));
        assertEquals(1,count("wx_bill_import"));assertEquals(1,count("wx_trade_bill_snapshot"));
        assertEquals("a".repeat(64),imports.selectImport("merchant-A","BASIC",accepted.getBillDate()).getSourceHash());
    }

    @Test void successfulTransactionCommitsTheSameExactFactsAndMarker() {
        transaction.execute(status->{
            details.insertDetail(trade());funds.insertSnapshot(fund());legacy.insertSnapshot(legacy());
            WxBillImport marker=marker("SUCCESS");marker.setSourceHash("c".repeat(64));marker.setRowCount(2);
            imports.upsertImport(marker);return null;
        });
        assertEquals(1,details.countMatchingDetail(trade()));assertEquals(1,funds.countMatchingSnapshot(fund()));
        assertEquals("SUCCESS",imports.selectImport("merchant-A","BASIC",Date.valueOf("2026-10-14")).getStatus());
        assertEquals(1,count("wx_trade_bill_snapshot"));
    }

    private int count(String table) { return db.queryForObject("select count(*) from "+table,Integer.class); }
    private static void assertMoney(String expected,BigDecimal actual) { assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(actual)); }
    private static void alter(Object bean,String field) {
        BeanWrapperImpl properties=new BeanWrapperImpl(bean);Object old=properties.getPropertyValue(field);
        Object changed=old instanceof BigDecimal?((BigDecimal)old).add(new BigDecimal("0.01"))
                :old instanceof java.util.Date?new java.util.Date(((java.util.Date)old).getTime()+ ("billDate".equals(field)?86400000L:1000L))
                :old==null?"changed":old+"-changed";
        properties.setPropertyValue(field,changed);
    }
    private static WxTradeBillDetail trade() {
        WxTradeBillDetail r=new WxTradeBillDetail();r.setBillDate(Date.valueOf("2026-10-14"));r.setMerchantId("merchant-A");
        r.setRecordKey("PAY:tx-1");r.setTradeTime(Timestamp.valueOf("2026-10-14 09:00:00"));r.setTransactionId("tx-1");
        r.setOutTradeNo("order-1");r.setTradeState("SUCCESS");r.setOrderAmount(new BigDecimal("0.10"));
        r.setSettlementAmount(new BigDecimal("0.08"));r.setCouponAmount(new BigDecimal("0.02"));
        r.setRequestedRefundAmount(BigDecimal.ZERO);r.setSettlementRefundAmount(BigDecimal.ZERO);r.setCouponRefundAmount(BigDecimal.ZERO);
        r.setFee(new BigDecimal("-0.01"));return r;
    }
    private static WxFundFlowBillSnapshot fund() {
        WxFundFlowBillSnapshot r=new WxFundFlowBillSnapshot();r.setBillDate(Date.valueOf("2026-10-14"));r.setMerchantId("merchant-A");
        r.setAccountType("BASIC");r.setAccountingTime(Timestamp.valueOf("2026-10-14 09:00:00"));r.setWxBusinessNo("wx-biz-1");
        r.setFlowNo("flow-1");r.setBusinessName("external transfer");r.setBusinessType("external kind");r.setDirection("收入");
        r.setAmount(new BigDecimal("0.10"));r.setBalance(new BigDecimal("-1.10"));r.setInitiator("source actor");r.setRemark(null);r.setVoucherNo("voucher-1");return r;
    }
    private static WxBillImport marker(String status) {
        WxBillImport r=new WxBillImport();r.setBillDate(Date.valueOf("2026-10-14"));r.setMerchantId("merchant-A");
        r.setBillType("BASIC");r.setStatus(status);r.setRowCount(0);r.setBalanceStatus("UNVERIFIED");return r;
    }
    private static WxTradeBillSnapshot legacy() {
        WxTradeBillSnapshot r=new WxTradeBillSnapshot();r.setBillDate(Date.valueOf("2026-10-14"));r.setWxTradeNo("tx-1");
        r.setOutTradeNo("order-1");r.setBizType(1);r.setAmount(new BigDecimal("0.10"));r.setTradeTime(Timestamp.valueOf("2026-10-14 09:00:00"));return r;
    }
}
