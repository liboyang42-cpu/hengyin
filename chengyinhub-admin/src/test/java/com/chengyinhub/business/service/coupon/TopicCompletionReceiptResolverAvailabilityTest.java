package com.chengyinhub.business.service.coupon;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.web.config.TopicCompletionCouponReceiptConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Disabling new issuance must not turn an already-versioned receipt into legacy guessing. */
public class TopicCompletionReceiptResolverAvailabilityTest {
    @Test void existingVersionedGrantWithMissingResolverCannotEnterHeuristicFallback()throws Exception {
        TopicCompletionCouponExactClawbackTest f=new TopicCompletionCouponExactClawbackTest();f.setup();
        try {
            long exact=f.grant();
            ReflectionTestUtils.setField(f.clawback,"completionCouponReceipts",null);
            assertThrows(IllegalStateException.class,()->f.reverse(7,100));
            assertEquals(0,f.witness.guesses);
            f.unchanged(exact);
        } finally { f.close(); }
    }
    @Test void installedProtocolWithMissingWriterCannotCreateUnversionedNewGrant()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {
            ReflectionTestUtils.setField(f.target,"receiptService",null);
            try { f.completion.grantOnce(100L,7L,8L); } catch(IllegalStateException expected) { }
            assertEquals(0,f.count("sms_coupon_history"),"Installed protocol may not silently downgrade to legacy issuance");
            f.empty();
        } finally { f.close(); }
    }

    @Test void alwaysResolverRemainsUsableWhenNewIssuanceProfileIsAbsent()throws Exception {
        TopicCompletionCouponExactClawbackTest f=new TopicCompletionCouponExactClawbackTest();f.setup();
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
            long exact=f.grant();ReflectionTestUtils.setField(f.f.target,"receiptService",null);
            context.getBeanFactory().registerSingleton("receiptMapper",f.f.mapper);
            context.getBeanFactory().registerSingleton("dataSource",f.f.f.db);
            context.register(TopicCompletionCouponHistoryResolver.class,TopicCompletionCouponReceiptConfiguration.class);context.refresh();
            assertTrue(context.getBeansOfType(TopicCompletionCouponReceiptService.class).isEmpty());
            TopicCompletionCouponHistoryResolving always=context.getBean("topicCompletionCouponHistoryResolver",TopicCompletionCouponHistoryResolving.class);
            ReflectionTestUtils.setField(f.clawback,"completionCouponReceipts",always);
            assertEquals("VOIDED",f.reverse(7,100).get("result"));assertEquals(2,f.status(exact));assertEquals(0,f.witness.guesses);
        } finally { f.close(); }
    }
    @Test void independentResolverDoesNotHaveAnActivationProfile() {
        assertNull(TopicCompletionCouponHistoryResolver.class.getAnnotation(org.springframework.context.annotation.Profile.class));
        assertNotNull(TopicCompletionCouponReceiptConfiguration.class.getAnnotation(org.springframework.context.annotation.Profile.class));
    }
    @Test void missingMandatoryProtocolGuardCannotIssueEvenWithActiveWriter()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {ReflectionTestUtils.setField(f.target,"receiptProtocol",null);assertThrows(IllegalStateException.class,()->f.completion.grantOnce(100L,7L,8L));f.empty();}finally{f.close();}
    }
    @Test void verifiedLegacySchemaWithoutReceiptOrMarkerRetainsLegacyClassification()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {f.f.jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");f.f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");
            boolean installed=new TransactionTemplate(f.f.manager).execute(s->f.receipts.protocolInstalled());assertFalse(installed);
            f.logs.insertIgnore(100L,7L,8L);assertNull(new TransactionTemplate(f.f.manager).execute(s->f.receipts.currentHistoryForClawback(100,7,8)));
        }finally{f.close();}
    }
    @Test void halfSchemaReceiptTableWithoutMarkerCannotIssueOrClassifyAsLegacy()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {f.f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");assertThrows(IllegalStateException.class,()->f.completion.grantOnce(100L,7L,8L));
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.f.manager).execute(s->f.receipts.currentHistoryForClawback(100,7,8)));f.empty();
        }finally{f.close();}
    }
    @Test void halfSchemaMarkerWithoutReceiptTableCannotDowngradeIssuance()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {f.f.jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");ReflectionTestUtils.setField(f.target,"receiptService",null);assertThrows(IllegalStateException.class,()->f.completion.grantOnce(100L,7L,8L));
            assertEquals(0,f.count("sms_coupon_history"));assertEquals(0,f.count("cms_topic_completion_coupon_reward_log"));assertEquals(0L,f.f.coupons.selectSmsCouponById(8L).getReceiveCount());
        }finally{f.close();}
    }
    @Test void metadataWildcardSimilarTableCannotSatisfyExactSchema()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {f.f.jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");f.f.jdbc.execute("CREATE TABLE cmsXtopicXcompletionXcouponXreceipt(id BIGINT)");
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.f.manager).execute(s->f.receipts.protocolInstalled()));
        }finally{f.close();}
    }
    @Test void viewCannotSubstituteAppendOnlyReceiptTable()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {f.f.jdbc.execute("DROP TABLE cms_topic_completion_coupon_receipt");f.f.jdbc.execute("CREATE VIEW cms_topic_completion_coupon_receipt AS SELECT id FROM cms_topic_completion_coupon_reward_log");
            assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.f.manager).execute(s->f.receipts.protocolInstalled()));
        }finally{f.close();}
    }
    @Test void schemaInspectionRequiresExistingDatasourceTransaction()throws Exception {
        TopicCompletionCouponReceiptTransactionTest f=new TopicCompletionCouponReceiptTransactionTest();f.setup();
        try {assertThrows(IllegalStateException.class,()->f.receipts.protocolInstalled());f.empty();}finally{f.close();}
    }

}
