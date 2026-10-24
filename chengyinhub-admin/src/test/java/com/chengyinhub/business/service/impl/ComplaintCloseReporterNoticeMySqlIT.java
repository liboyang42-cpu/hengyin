package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.support.ComplaintCloseReporterNotice;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real complaint/financial/IM mappers and the existing audit owner; no new production schema.
 * Reuses controlled scratch and the two existing canonical audit schema statements.
 * Policy, security filters and real WX/alert webhooks are not exercised. */
class ComplaintCloseReporterNoticeMySqlIT {
    private static final String KEY="complaint-close-reporter:1",TITLE="投诉工单已处理";
    private static final String BODY="工单#1已关闭，请在投诉记录查看处理说明。";

    @Test void nullRecipientClosesBusinessAndPersistsARejectedSubmissionBeforeAlert() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            assertEquals(200,h.controller.close(Map.of("id",1L,"respondentType","merchant","respondentMemberId",200L,
                    "faultRatio",100,"deductAmount","50","note","completed")).get("code"));
            assertClosed(h); Long receipt=assertRejected(h,"MISSING");
            verifyNoInteractions(r.alarm,h.notice.wx,h.oldNotices);
            h.notice.wakes.get(0).run();
            verify(r.alarm).alarm(eq("投诉关闭通知不可投递"),contains("auditId="+receipt));
            assertEquals(0,h.db.queryForObject("select count(*) from im_notification_intent where event_key=?",Integer.class,KEY));
            var replay=h.notice.tx.execute(tx -> r.owner.submitReporter(1L,null,999L,TITLE,BODY));
            assertEquals(ComplaintCloseReporterNotice.REJECTED,replay.state()); assertEquals(receipt,replay.referenceId());
            assertThrows(RuntimeException.class,() -> h.notice.tx.execute(tx -> r.owner.submitReporter(1L,null,999L,TITLE,"changed body")));
            assertEquals(receipt,assertRejected(h,"MISSING")); assertClosed(h);
        }
    }

    @Test void zeroAndNegativeRecipientsRemainNotificationRejectionsWithoutVetoingTheClose() throws Exception {
        for (long recipient:new long[]{0L,-7L}) {
            try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
                Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=? where id=1",recipient);
                assertEquals(1,h.closeComplaint()); assertClosed(h); assertRejected(h,"NON_POSITIVE");
                verifyNoInteractions(r.alarm,h.notice.wx);
                assertEquals(0,h.db.queryForObject("select count(*) from im_notification_intent where event_key=?",Integer.class,KEY));
            }
        }
    }

    @Test void outerFailureRollsBackFinancialRowsRejectionReceiptAndBothPostCommitActions() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            assertThrows(IllegalStateException.class,() -> h.notice.tx.executeWithoutResult(tx -> {
                assertEquals(1,h.closeComplaint()); assertRejected(h,"MISSING");
                assertTrue(h.notice.wakes.isEmpty()); verifyNoInteractions(r.alarm,h.notice.wx);
                throw new IllegalStateException("fixture original transaction failed");
            }));
            h.assertAdvance(0,"0.00",0);
            assertEquals(new BigDecimal("100.00"),h.db.queryForObject("select balance from ums_member where id=200",BigDecimal.class));
            assertEquals(0,h.notice.count("business_audit_event"));
            assertEquals(0,h.notice.count("im_notification_intent")); assertEquals(0,h.notice.count("committed_official_notice_wx"));
            assertTrue(h.notice.wakes.isEmpty()); verifyNoInteractions(r.alarm,h.notice.wx);
        }
    }

    @Test void exactRejectedReplayReadsTheOriginalAuditIdentityWithoutAnotherRow() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            assertEquals(1,h.closeComplaint()); Long auditId=assertRejected(h,"MISSING");
            Long operator=h.db.queryForObject("select actor_member_id from business_audit_event where id=?",Long.class,auditId);
            var replay=h.notice.tx.execute(status -> r.owner.submitReporter(1L,null,operator,TITLE,BODY));
            assertEquals(ComplaintCloseReporterNotice.REJECTED,replay.state());
            assertEquals(auditId,replay.referenceId()); assertEquals(1,h.notice.count("business_audit_event"));
            assertClosed(h);
        }
    }

    @Test void auditSqlFailureStillRollsBackTheOriginalCloseInsteadOfClaimingARecordedRejection() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            // Controlled disposable scratch only: force the actual owner INSERT to fail at the database boundary.
            h.db.execute("create trigger fixture_rejection_write_failure before insert on business_audit_event "
                    + "for each row signal sqlstate '45000' set message_text='fixture audit write failed'");
            assertThrows(RuntimeException.class,h::closeComplaint);
            h.assertAdvance(0,"0.00",0);
            assertEquals(new BigDecimal("100.00"),h.db.queryForObject("select balance from ums_member where id=200",BigDecimal.class));
            assertEquals(0,h.notice.count("business_audit_event")); assertEquals(0,h.notice.count("im_notification_intent"));
            assertEquals(0,h.notice.count("committed_official_notice_wx")); assertTrue(h.notice.wakes.isEmpty());
            verifyNoInteractions(r.alarm,h.notice.wx);
        }
    }

    @Test void rejectedAlertQueueCannotLoseTheCommittedReceiptOrKeepAnOtherwiseCompletedComplaintOpen() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            doThrow(new RejectedExecutionException("fixture queue unavailable"))
                    .when(h.notice.executor).schedule(any(Runnable.class),eq(10L),eq(TimeUnit.MILLISECONDS));
            assertEquals(1,h.closeComplaint()); assertClosed(h); Long receipt=assertRejected(h,"MISSING");
            assertThrows(IllegalStateException.class,h::closeComplaint);
            h.notice.recover(); assertEquals(receipt,assertRejected(h,"MISSING")); assertClosed(h);
            assertEquals(1,h.notice.count("im_message"));
            assertEquals(200L,h.db.queryForObject("select receiver_id from im_message",Long.class));
            verifyNoInteractions(r.alarm); // Persistent audit, not an invented delivered alert.
        }
    }

    @Test void rejectionDoesNotRearmOrOverwriteAnOriginalAcceptedOrUnknownExternalIdentity() throws Exception {
      for (boolean accepted:new boolean[]{false,true}) {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            attach(h);
            Long original=h.notice.enqueue(KEY,100L,TITLE,BODY);
            when(h.notice.wx.send(any())).thenReturn(accepted); h.notice.notice.deliver(original);
            String originalState=accepted ? "ACCEPTED" : "UNKNOWN";
            assertEquals(originalState,h.notice.state(original));
            String originalOwner=h.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,original);
            Long originalMessage=h.db.queryForObject("select message_id from im_notification_intent where event_key=?",Long.class,KEY);
            h.db.update("update coop_complaint set reporter_member_id=null where id=1");
            assertEquals(1,h.closeComplaint()); assertRejected(h,"MISSING"); h.notice.recover(); h.notice.recover();
            assertEquals(originalState,h.notice.state(original));
            assertEquals(originalOwner,h.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,original));
            assertEquals(originalMessage,h.db.queryForObject("select message_id from im_notification_intent where event_key=?",Long.class,KEY));
            verify(h.notice.wx).send(argThat(message -> message.memberId().equals(100L)));
            assertEquals(1,h.db.queryForObject("select unread_count from im_conversation_member where member_id=100",Integer.class));
            assertClosed(h);
        }
      }
    }

    @Test void validReporterUsesTheOriginalFrozenIntentAndDoesNotCreateARejectionAudit() throws Exception {
        try (var h=new CoopComplaintCommittedNoticeMySqlIT.Harness()) {
            Reporter r=attach(h); assertEquals(1,h.closeComplaint()); assertClosed(h);
            assertEquals(0,h.notice.count("business_audit_event"));
            assertEquals(2,h.notice.count("im_notification_intent")); assertEquals(2,h.notice.count("committed_official_notice_wx"));
            assertEquals(0,h.notice.count("im_message")); verifyNoInteractions(r.alarm,h.notice.wx);
            h.db.update("update coop_complaint set reporter_member_id=999 where id=1"); h.notice.recover();
            assertEquals(TITLE+"\n"+BODY,h.db.queryForObject("select content from im_message where receiver_id=100",String.class));
            assertEquals(1,h.db.queryForObject("select unread_count from im_conversation_member where member_id=100",Integer.class));
            assertEquals("DELIVERED",h.db.queryForObject("select status from im_notification_intent where event_key=?",String.class,KEY));
            verify(h.notice.wx).send(argThat(message -> message.memberId().equals(100L)
                    && TITLE.equals(message.get("title")) && BODY.equals(message.get("sub"))));
            assertEquals(0,h.notice.count("business_audit_event")); verifyNoInteractions(r.alarm);
        }
    }

    private static Reporter attach(CoopComplaintCommittedNoticeMySqlIT.Harness h) throws Exception {
        new ResourceDatabasePopulator(new FileSystemResource(h.notice.sqlRoot.resolve("migration_business_audit_event_20260823.sql")))
                .execute(h.notice.manager.getDataSource());
        String governance=Files.readString(h.notice.sqlRoot.resolve("migration_approval_governance_20260828.sql"));
        var statement=Pattern.compile("ALTER TABLE business_audit_event\\s+ADD COLUMN actor_type.*?;",Pattern.DOTALL).matcher(governance);
        assertTrue(statement.find(),"canonical actor namespace/request-key ALTER must exist");
        h.db.execute(statement.group()); // Exact existing canonical statement, not a new/loosened scratch schema.
        assertEquals("NO",h.db.queryForObject("select is_nullable from information_schema.columns "
                +"where table_schema=database() and table_name='business_audit_event' and column_name='request_id'",String.class));
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(h.notice.manager.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));
        BusinessAuditEventMapper mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BusinessAuditEventMapper.class);
        AlarmService alarm=mock(AlarmService.class);
        ComplaintCloseReporterNotice target=new ComplaintCloseReporterNotice(h.notice.notice,mapper,alarm,h.notice.executor);
        ProxyFactory proxy=new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(h.notice.manager,new AnnotationTransactionAttributeSource()));
        ComplaintCloseReporterNotice owner=(ComplaintCloseReporterNotice)proxy.getProxy();
        ReflectionTestUtils.setField(h.target,"complaintCloseReporterNotice",owner); return new Reporter(owner,alarm);
    }

    private static Long assertRejected(CoopComplaintCommittedNoticeMySqlIT.Harness h,String kind) {
        assertEquals(1,h.notice.count("business_audit_event"));
        Map<String,Object> row=h.db.queryForMap("select * from business_audit_event");
        assertEquals("PLATFORM",row.get("domain_type")); assertEquals("COMPLAINT_CLOSE_NOTICE_REJECTED",row.get("action"));
        assertEquals(KEY,row.get("request_id")); assertEquals("COOP_COMPLAINT",row.get("target_type"));
        assertEquals("SYS_USER",row.get("actor_type")); assertEquals(999L,((Number)row.get("actor_member_id")).longValue());
        assertEquals("INVALID_RECIPIENT",row.get("reason"));
        var after=JSON.parseObject((String)row.get("after_json"));
        assertEquals(ComplaintCloseReporterNotice.REJECTED,after.getString("submission"));
        assertEquals(kind,after.getString("recipientKind")); assertEquals(KEY,after.getString("eventKey"));
        assertTrue(after.getString("requestHash").matches("[0-9a-f]{64}"));
        assertFalse(after.containsKey("body")); assertFalse(after.containsKey("title"));
        return ((Number)row.get("id")).longValue();
    }

    private static void assertClosed(CoopComplaintCommittedNoticeMySqlIT.Harness h) {
        h.assertAdvance(3,"0.00",1);
        assertEquals(new BigDecimal("50.00"),h.db.queryForObject("select balance from ums_member where id=200",BigDecimal.class));
    }

    private record Reporter(ComplaintCloseReporterNotice owner,AlarmService alarm) { }
}
