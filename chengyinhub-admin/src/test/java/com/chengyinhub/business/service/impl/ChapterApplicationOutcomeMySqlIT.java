package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.ChapterApplicationInviteNotice;
import com.chengyinhub.business.service.support.ChapterApplicationInviteNotice.Outcome;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production application/notice/IM mappers and Spring application transaction on controlled MySQL.
 * Qualification/node work/task mapper are boundaries; topic/chapter physical locks are JDBC-backed.
 * No full audit-task, qualification, financial, HTTP, process-kill or real WX acceptance is claimed. */
class ChapterApplicationOutcomeMySqlIT {
    @Test void reviewPersistsTheWinningRoundAndFrozenReasonAcrossReapplication() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var first=f.service.apply(73L,2001L,"first");
            f.service.audit(first.getId(),1001L,false,"original reason");
            assertThrows(IllegalStateException.class,() -> f.service.audit(first.getId(),1001L,false,"changed retry"));
            f.storage.db.update("update merchant_chapter_application set audit_remark='later live edit' where id=?",first.getId());
            var second=f.service.apply(73L,2001L,"second"); assertEquals(2L,second.getApplicationRound());
            f.service.audit(second.getId(),1001L,true,null);
            assertEquals(2,f.storage.count("im_notification_intent"));
            f.storage.recover();
            assertEquals("章节申请审核结果\n章节申请未通过：original reason",content(f,key(first.getId(),1,"review")));
            assertEquals("章节申请审核结果\n章节申请已通过",content(f,key(first.getId(),2,"review")));
            assertEquals(2,f.storage.db.queryForObject("select unread_count from im_conversation_member where member_id=2001",Integer.class));
            verifyNoInteractions(f.otherNotices);
        }
    }

    @Test void withdrawalReplayDoesNotEmitAndRestoredApplicationUsesTheNextRound() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var first=f.service.apply(73L,2001L,"first");
            f.service.withdraw(first.getId(),2001L); f.service.withdraw(first.getId(),2001L);
            assertEquals(1L,f.round(first.getId())); assertEquals(1,f.storage.count("im_notification_intent"));
            var restored=f.service.apply(73L,2001L,"restored");
            assertEquals(2L,restored.getApplicationRound()); f.service.withdraw(restored.getId(),2001L);
            assertEquals(2,f.storage.count("im_notification_intent")); f.storage.recover();
            assertEquals("章节申请已撤回\n商家已撤回章节承接申请",content(f,key(first.getId(),1,"withdraw")));
            assertEquals("章节申请已撤回\n商家已撤回章节承接申请",content(f,key(first.getId(),2,"withdraw")));
            assertEquals(2,f.storage.db.queryForObject("select unread_count from im_conversation_member where member_id=1001",Integer.class));
        }
    }

    @Test void invitationResponseReplayKeepsTheOriginalDecisionReceiptAndCannotRevokeApprovedState() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var invite=f.invite();
            f.service.respondInvite(invite.getId(),2001L,true); f.service.respondInvite(invite.getId(),2001L,true);
            assertThrows(IllegalStateException.class,() -> f.service.revokeInvite(invite.getId(),1001L));
            assertEquals(1L,f.round(invite.getId())); assertEquals(2,f.storage.count("im_notification_intent"));
            f.storage.recover(); assertEquals("章节邀请处理结果\n商家已接受章节邀请",content(f,key(invite.getId(),1,"invite-decision")));
            assertEquals(1,f.storage.db.queryForObject("select unread_count from im_conversation_member where member_id=1001",Integer.class));
        }
    }

    @Test void failedDecisionAndWithdrawalCasProduceNeitherDeliveryNorLegacyAudit() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var application=f.service.apply(73L,2001L,"first");
            MerchantChapterApplicationMapper failed=mock(MerchantChapterApplicationMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(f.mapper));
            doReturn(0).when(failed).updateAudit(anyLong(),anyInt(),any(),anyLong(),any());
            doReturn(0).when(failed).withdrawPending(anyLong(),anyLong());
            ReflectionTestUtils.setField(f.target,"applicationMapper",failed);
            assertThrows(IllegalStateException.class,() -> f.service.audit(application.getId(),1001L,false,"reason"));
            assertThrows(IllegalStateException.class,() -> f.service.withdraw(application.getId(),2001L));
            assertEquals(0,f.storage.count("im_notification_intent")); assertEquals(0,f.storage.count("committed_official_notice_wx"));
            assertEquals(0,f.storage.count("business_audit_event")); assertTrue(f.storage.wakes.isEmpty());
            assertEquals(0,f.storage.db.queryForObject("select status from merchant_chapter_application",Integer.class));
            assertEquals(0,f.storage.db.queryForObject("select del_flag from merchant_chapter_application",Integer.class));
            assertEquals(1L,f.round(application.getId())); verifyNoInteractions(f.storage.wx);
        }
    }

    @Test void outerRollbackRestoresDecisionRoundAndRemovesNotificationOrUnverifiedFact() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var application=f.service.apply(73L,2001L,"first");
            assertThrows(IllegalStateException.class,() -> f.storage.tx.executeWithoutResult(tx -> {
                f.service.audit(application.getId(),1001L,false,"reason"); throw new IllegalStateException("later failure");
            }));
            assertEquals(0,f.storage.db.queryForObject("select status from merchant_chapter_application",Integer.class));
            assertEquals(1L,f.round(application.getId())); assertEquals(0,f.storage.count("im_notification_intent"));
            assertTrue(f.storage.wakes.isEmpty()); verifyNoInteractions(f.storage.wx);
        }
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture(true)) {
            AlarmService alarm=enable(f);
            assertThrows(IllegalStateException.class,() -> f.storage.tx.executeWithoutResult(tx -> {
                f.service.respondInvite(91L,2001L,false); assertEquals(1,f.storage.count("business_audit_event"));
                throw new IllegalStateException("later legacy failure");
            }));
            assertNull(f.round(91L)); assertEquals(0,f.storage.db.queryForObject("select status from merchant_chapter_application",Integer.class));
            assertEquals(0,f.storage.count("business_audit_event")); assertEquals(0,f.storage.count("im_notification_intent"));
            assertTrue(f.storage.wakes.isEmpty()); verifyNoInteractions(alarm,f.storage.wx);
        }
    }

    @Test void legacyNullDecisionRemainsBusinessValidButNeverInventsARoundOrAnExternalAttempt() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture(true)) {
            AlarmService alarm=enable(f);
            assertEquals(2,f.service.respondInvite(91L,2001L,false).getStatus());
            f.service.respondInvite(91L,2001L,false);
            assertNull(f.round(91L)); assertEquals(1,f.storage.count("business_audit_event"));
            var fact=JSON.parseObject(f.storage.db.queryForObject("select after_json from business_audit_event",String.class));
            assertEquals("UNVERIFIABLE_LEGACY_ROUND",fact.getString("submission")); assertFalse(fact.getBooleanValue("roundKnown"));
            assertFalse(fact.containsKey("body")); assertFalse(fact.containsKey("title"));
            assertEquals(0,f.storage.count("im_notification_intent")); assertEquals(0,f.storage.count("committed_official_notice_wx"));
            assertEquals(1,f.storage.wakes.size()); verifyNoInteractions(alarm,f.storage.wx);
            var owner=(ChapterApplicationInviteNotice)ReflectionTestUtils.getField(f.target,"inviteNotice");
            f.storage.tx.executeWithoutResult(tx -> owner.submitOutcome(41L,91L,null,Outcome.INVITE_DECISION,1001L,
                    "章节邀请处理结果","商家已拒绝章节邀请"));
            assertEquals(1,f.storage.count("business_audit_event")); assertEquals(1,f.storage.wakes.size());
            assertThrows(RuntimeException.class,() -> f.storage.tx.executeWithoutResult(tx ->
                    owner.submitOutcome(41L,91L,null,Outcome.INVITE_DECISION,1001L,"章节邀请处理结果","changed input")));
            assertEquals(1,f.storage.count("business_audit_event"));
            f.storage.wakes.get(0).run(); verify(alarm).alarm(eq("申请通知未提交"),contains("UNVERIFIABLE_LEGACY_ROUND"));
            var next=f.service.apply(73L,2001L,"observed new application"); assertEquals(1L,next.getApplicationRound());
            f.service.respondInvite(91L,2001L,true); f.storage.recover();
            assertEquals(1,f.storage.count("business_audit_event")); assertEquals(1,f.storage.count("im_notification_intent"));
            assertEquals("章节邀请处理结果\n商家已接受章节邀请",content(f,key(91L,1,"invite-decision")));
        }
    }

    @Test void revokeThenReinviteKeepsTheOldUnknownOutcomeOwnerAndMessageIdentity() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var first=f.invite(); f.storage.notice.deliver(f.noticeId(first.getId(),1));
            f.service.revokeInvite(first.getId(),1001L); f.service.revokeInvite(first.getId(),1001L);
            Long old=f.storage.db.queryForObject("select id from committed_official_notice_wx where event_key=?",Long.class,key(first.getId(),1,"invite-revoke"));
            when(f.storage.wx.send(any())).thenReturn(false); f.storage.notice.deliver(old);
            String owner=f.storage.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,old);
            Long message=f.storage.db.queryForObject("select message_id from im_notification_intent where event_key=?",Long.class,key(first.getId(),1,"invite-revoke"));
            assertEquals(2L,f.invite().getApplicationRound());
            when(f.storage.wx.send(any())).thenReturn(true); f.storage.recover(); f.storage.notice.deliver(old); f.storage.recover();
            assertEquals("UNKNOWN",f.storage.state(old));
            assertEquals(owner,f.storage.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,old));
            assertEquals(message,f.storage.db.queryForObject("select message_id from im_notification_intent where event_key=?",Long.class,key(first.getId(),1,"invite-revoke")));
            assertEquals(3,f.storage.count("im_message")); verify(f.storage.wx,times(3)).send(any());
        }
    }

    @Test void rrOldPlainApplicationCannotStampASecondRoundDecisionWithTheFirstRound() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var first=f.invite();
            MerchantChapterApplicationMapper observed=mock(MerchantChapterApplicationMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(f.mapper));
            doAnswer(call -> {
                var old=f.mapper.selectMerchantChapterApplicationById(first.getId());
                assertEquals(1L,old.getApplicationRound(),"actual ordinary RR read must still see first round"); return old;
            }).when(observed).selectMerchantChapterApplicationById(first.getId());
            ReflectionTestUtils.setField(f.target,"applicationMapper",observed);
            f.storage.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                f.storage.tx.executeWithoutResult(tx -> {
                    assertEquals(1L,f.round(first.getId()));
                    try { worker.submit(() -> { f.service.revokeInvite(first.getId(),1001L); return f.invite(); }).get(15,TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    assertEquals(2L,f.service.respondInvite(first.getId(),2001L,true).getApplicationRound());
                });
                assertEquals(0,f.storage.db.queryForObject("select count(*) from im_notification_intent where event_key=?",Integer.class,key(first.getId(),1,"invite-decision")));
                assertEquals(1,f.storage.db.queryForObject("select count(*) from im_notification_intent where event_key=?",Integer.class,key(first.getId(),2,"invite-decision")));
                assertEquals(2L,f.round(first.getId()));
            } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS)); }
        }
    }

    @Test void withdrawalAcquiresPhysicalParentsBeforeApplicationAndPreservesMissingParentReplay() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var application=f.service.apply(73L,2001L,"first"); AtomicBoolean check=new AtomicBoolean(true);
            MerchantChapterApplicationMapper observed=mock(MerchantChapterApplicationMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(f.mapper));
            doAnswer(call -> {
                if (check.get()) { mustBeLocked(f,"cms_topic",41L); mustBeLocked(f,"cms_topic_chapter",73L); }
                return f.mapper.selectByIdIncludingDeletedForUpdate(application.getId());
            }).when(observed).selectByIdIncludingDeletedForUpdate(application.getId());
            ReflectionTestUtils.setField(f.target,"applicationMapper",observed);
            f.service.withdraw(application.getId(),2001L); check.set(false);
            f.storage.db.update("delete from cms_topic_chapter where id=73"); f.storage.db.update("delete from cms_topic where id=41");
            assertEquals(2,f.service.withdraw(application.getId(),2001L).getDelFlag());
            assertEquals(1L,f.round(application.getId())); assertEquals(1,f.storage.count("im_notification_intent"));
        }
    }

    @Test void concurrentApprovalAndWithdrawalCommitOneOriginalCasAndOneOutcome() throws Exception {
        try (var f=new ChapterApplicationRoundMySqlIT.Fixture()) {
            enable(f); var application=f.service.apply(73L,2001L,"first");
            ExecutorService workers=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
            try {
                Future<Boolean> audit=workers.submit(() -> {
                    assertTrue(start.await(10,TimeUnit.SECONDS));
                    try { f.service.audit(application.getId(),1001L,true,null); return true; } catch (IllegalStateException lost) { return false; }
                });
                Future<Boolean> withdraw=workers.submit(() -> {
                    assertTrue(start.await(10,TimeUnit.SECONDS));
                    try { f.service.withdraw(application.getId(),2001L); return true; } catch (IllegalStateException lost) { return false; }
                });
                start.countDown(); boolean approved=audit.get(20,TimeUnit.SECONDS),withdrawn=withdraw.get(20,TimeUnit.SECONDS);
                assertNotEquals(approved,withdrawn); assertEquals(1L,f.round(application.getId()));
                assertEquals(approved?1:0,f.storage.db.queryForObject("select status from merchant_chapter_application",Integer.class));
                assertEquals(approved?0:2,f.storage.db.queryForObject("select del_flag from merchant_chapter_application",Integer.class));
                assertEquals(1,f.storage.count("im_notification_intent")); assertEquals(0,f.storage.count("business_audit_event"));
                assertEquals(key(application.getId(),1,approved?"review":"withdraw"),f.storage.db.queryForObject("select event_key from im_notification_intent",String.class));
                f.storage.recover(); assertEquals(1,f.storage.count("im_message"));
            } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS)); }
        }
    }

    private static AlarmService enable(ChapterApplicationRoundMySqlIT.Fixture f) throws Exception {
        new ResourceDatabasePopulator(new FileSystemResource(f.storage.sqlRoot.resolve("migration_business_audit_event_20260823.sql")))
                .execute(f.storage.manager.getDataSource());
        String governance=Files.readString(f.storage.sqlRoot.resolve("migration_approval_governance_20260828.sql"));
        var alter=Pattern.compile("ALTER TABLE business_audit_event\\s+ADD COLUMN actor_type.*?;",Pattern.DOTALL).matcher(governance);
        assertTrue(alter.find()); f.storage.db.execute(alter.group());
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(f.storage.manager.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(BusinessAuditEventMapper.class);
        AlarmService alarm=mock(AlarmService.class);
        ChapterApplicationInviteNotice notice=new ChapterApplicationInviteNotice(f.storage.notice);
        ReflectionTestUtils.setField(notice,"audit",mapper); ReflectionTestUtils.setField(notice,"alarm",alarm);
        ReflectionTestUtils.setField(notice,"executor",f.storage.executor);
        ReflectionTestUtils.setField(f.target,"inviteNotice",notice);
        return alarm;
    }

    private static String key(Long id,long round,String event) { return "chapter-app:41:"+id+":"+round+":"+event; }
    private static String content(ChapterApplicationRoundMySqlIT.Fixture f,String key) {
        return f.storage.db.queryForObject("select m.content from im_message m join im_notification_intent i on i.message_id=m.id where i.event_key=?",String.class,key);
    }
    private static void mustBeLocked(ChapterApplicationRoundMySqlIT.Fixture f,String table,long id) throws Exception {
        try (var connection=f.storage.manager.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("select id from "+table+" where id=? for update nowait")) {
                statement.setLong(1,id); SQLException blocked=assertThrows(SQLException.class,statement::executeQuery);
                assertEquals(3572,blocked.getErrorCode(),"original transaction must already hold this physical parent");
            } finally { connection.rollback(); }
        }
    }
}
