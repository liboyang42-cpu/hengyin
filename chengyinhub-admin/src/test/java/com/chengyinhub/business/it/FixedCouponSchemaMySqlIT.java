package com.chengyinhub.business.it;

import com.chengyinhub.business.service.coupon.*;
import com.chengyinhub.web.config.TopicCompletionCouponReceiptConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC metadata, actual SQL transactions; no production activation or authority claim. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class FixedCouponSchemaMySqlIT extends FixedCouponMySqlFixture {
    private void empty() {
        for(String table:new String[]{"sms_coupon_history","cms_topic_completion_coupon_reward_log","sms_coupon_terms_snapshot"})
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        assertEquals(0L,coupons.selectSmsCouponById(8L).getReceiveCount());
    }
    @Test void databaseIdentityAndMetadataAreObservedFromActualConnection() throws Exception {
        Map<String,Object> identity=jdbc.queryForMap("SELECT VERSION() AS version, @@version_comment AS version_comment, @@lower_case_table_names AS lower_case_table_names, DATABASE() AS database_name");
        try(java.sql.Connection connection=source.getConnection()) {
            java.sql.DatabaseMetaData metadata=connection.getMetaData();
            assertEquals("MySQL",metadata.getDatabaseProductName());
            assertTrue(com.chengyinhub.business.service.MysqlIntegrationTestConfig.isMysql8OrNewer((String)identity.get("version")));
            assertEquals(identity.get("database_name"),connection.getCatalog());
            assertEquals(isolatedDatabaseName(),identity.get("database_name"));
            int caseMode=((Number)identity.get("lower_case_table_names")).intValue();
            assertTrue(caseMode>=0 && caseMode<=2);
            System.out.println("FIXED_COUPON_MYSQL_METADATA "+identity+" catalog="+connection.getCatalog()+" schema="+connection.getSchema()
                +" storesLowerCaseIdentifiers="+metadata.storesLowerCaseIdentifiers()+" storesUpperCaseIdentifiers="+metadata.storesUpperCaseIdentifiers());
        }
    }
    @Test void completeSchemaAllowsExactReceiptAndMarker() {
        assertTrue(installed()); assertTrue(completion.grantOnce(100L,7L,8L));
        assertEquals(1,jdbc.queryForObject("SELECT receipt_contract_version FROM cms_topic_completion_coupon_reward_log",Integer.class));
        Long exact=new TransactionTemplate(manager).execute(s->resolver.currentHistoryForClawback(100,7,8));
        assertNotNull(exact); assertEquals(7L,histories.selectSmsCouponHistoryById(exact).getMemberId());
    }
    @Test void absentBothOptionalObjectsKeepsVerifiedLegacyGrant() {
        jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");
        jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");
        ReflectionTestUtils.setField(target,"receiptService",null);
        assertFalse(installed()); assertTrue(completion.grantOnce(100L,7L,8L));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_history",Integer.class));
    }
    @Test void receiptWithoutMarkerFailsClosed() {
        jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");
        assertThrows(IllegalStateException.class,this::installed);
        assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();
    }
    @Test void markerWithoutReceiptFailsClosedEvenWithoutWriter() {
        jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt"); ReflectionTestUtils.setField(target,"receiptService",null);
        assertThrows(IllegalStateException.class,this::installed);
        assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();
    }
    @Test void exactNameViewIsNeverReceiptStorage() {
        jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");
        jdbc.execute("CREATE VIEW cms_topic_completion_coupon_receipt AS SELECT id FROM cms_topic_completion_coupon_reward_log");
        assertThrows(IllegalStateException.class,this::installed);
        assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();
    }
    @Test void wildcardLookalikeDoesNotSatisfyExactReceiptName() {
        jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");
        jdbc.execute("CREATE TABLE cmsXtopicXcompletionXcouponXreceipt(id BIGINT PRIMARY KEY)");
        assertThrows(IllegalStateException.class,this::installed);empty();
    }
    @Test void missingLogFailsClosed() {
        jdbc.execute("DROP TABLE cms_topic_completion_coupon_reward_log");
        assertThrows(IllegalStateException.class,this::installed);
    }
    @Test void installedProtocolWithoutIssuanceWriterCannotFallBack() {
        ReflectionTestUtils.setField(target,"receiptService",null);
        assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();
    }
    @Test void mandatoryResolverCannotBeOmittedEvenWithWriter() {
        ReflectionTestUtils.setField(target,"receiptProtocol",null);
        assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));empty();
    }
    @Test void resolverRemainsAvailableAndRevokesExactHistoryWhenIssuanceProfileAbsent() {
        assertTrue(completion.grantOnce(100L,7L,8L));
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("receiptMapper",receiptMapper);
            context.getBeanFactory().registerSingleton("dataSource",source);
            context.register(TopicCompletionCouponHistoryResolver.class,TopicCompletionCouponReceiptConfiguration.class);
            context.refresh();
            assertTrue(context.getBeansOfType(TopicCompletionCouponReceiptService.class).isEmpty());
            TopicCompletionCouponHistoryResolver always=context.getBean(TopicCompletionCouponHistoryResolver.class);
            ReflectionTestUtils.setField(clawback,"completionCouponReceipts",always);
            Map<String,Object> result=new TransactionTemplate(manager).execute(s->ReflectionTestUtils.invokeMethod(clawback,"voidCompletionCoupon",7L,100L,500L));
            assertEquals("VOIDED",result.get("result"));
            assertEquals(2,histories.selectSmsCouponHistoryById(((Number)result.get("historyId")).longValue()).getUseStatus());
        }
    }
    @Test void noTransactionAndDifferentDatasourceCannotInspectOrGrant() {
        assertThrows(IllegalStateException.class,()->resolver.protocolInstalled());
        DriverManagerDataSource other=new DriverManagerDataSource();
        TopicCompletionCouponHistoryResolver foreign=new TopicCompletionCouponHistoryResolver(receiptMapper,other);
        TopicCompletionCouponReceiptService foreignWriter=new TopicCompletionCouponReceiptService(receiptMapper,legacy,other);
        new TransactionTemplate(manager).execute(s->{
            assertThrows(IllegalStateException.class,()->foreign.protocolInstalled());
            assertThrows(IllegalStateException.class,()->foreignWriter.grantForNewCompletion(100,7,8));return null;
        });empty();
    }
    @Test void receiptReadAndImmutableSnapshotRemainDistinctRealStorageContracts() {
        assertTrue(completion.grantOnce(100L,7L,8L));
        long log=jdbc.queryForObject("SELECT id FROM cms_topic_completion_coupon_reward_log",Long.class);
        assertEquals("NOT_PROVIDED",receipts.owned(7,log).get("termsAvailability"));
        CouponTermsSnapshotService.Read first=snapshotService.recordNewGrant(intent("test-only-committed-proof","nonce1"));
        assertEquals(CouponTermsSnapshotService.Availability.RECORDED,first.availability);
        jdbc.update("UPDATE sms_coupon SET description='later edit' WHERE id=8");
        assertEquals(first.terms.getJson(),snapshotService.readOwned(7,first.historyId).terms.getJson());
        assertNull(snapshotService.readOwned(99,first.historyId));
        assertEquals(2L,coupons.selectSmsCouponById(8L).getReceiveCount());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sms_coupon_terms_snapshot",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cms_topic_completion_coupon_receipt",Integer.class));
    }
}
