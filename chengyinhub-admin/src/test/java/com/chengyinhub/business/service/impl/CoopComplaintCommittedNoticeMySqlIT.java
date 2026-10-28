package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopComplaint;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.service.ICoopComplaintService;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.support.ComplaintCloseReporterNotice;
import com.chengyinhub.web.controller.business.CoopComplaintController;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual complaint producer, production financial/IM mappers and Spring transactions on the same MySQL connection.
 * Reuses the old financial fixture's database/setup only; no old test is invoked or silently counted as passed.
 * Cooldown/debt policy, controller security filters and WX are boundaries; no HTTP/real external call is claimed. */
class CoopComplaintCommittedNoticeMySqlIT {
    @Test void actualControllerAdvanceCommitsOriginalRecipientAndFrozenIntentsBeforeAnyChannelAttempt() throws Exception {
        try (var h = new Harness()) {
            assertEquals(200, h.controller.advance(Map.of("id", 1L, "amount", "50")).get("code"));
            h.assertAdvance(1, "50.00", 1);
            assertEquals(1, h.notice.count("im_notification_intent"));
            assertEquals(1, h.notice.count("committed_official_notice_wx"));
            assertEquals(0, h.notice.count("im_message")); verifyNoInteractions(h.notice.wx, h.oldNotices);
            Long detailId = h.db.queryForObject("select id from ums_member_balance_detail", Long.class);
            verify(h.cooldown).registerFromPositiveComplaintDetail(detailId, 100L, 1L, new BigDecimal("50"));
            h.db.update("update coop_complaint set reporter_member_id=999,handle_note='changed after commit' where id=1");
            h.notice.recover(); h.notice.oneMessage();
            assertEquals(100L, h.db.queryForObject("select receiver_id from im_message", Long.class));
            assertEquals("投诉处理有进展\n工单#1已先行垫付 ¥50，请在投诉记录查看处理进度。",
                    h.db.queryForObject("select content from im_message", String.class));
            verify(h.notice.wx).send(any());
        }
    }

    @Test void outerTransactionFailureRollsBackActualAdvanceBalanceDetailAndBothIntents() throws Exception {
        try (var h = new Harness()) {
            assertThrows(IllegalStateException.class, () -> h.notice.tx.executeWithoutResult(status -> {
                assertEquals(1, h.service.advance(1L, new BigDecimal("50"), 999L));
                assertEquals(1, h.notice.count("im_notification_intent"));
                assertEquals(1, h.notice.count("committed_official_notice_wx"));
                assertTrue(h.notice.wakes.isEmpty()); verifyNoInteractions(h.notice.wx);
                throw new IllegalStateException("fixture failure after actual producer returned");
            }));
            h.assertAdvance(0, "0.00", 0);
            assertEquals(0, h.notice.count("im_notification_intent"));
            assertEquals(0, h.notice.count("committed_official_notice_wx"));
            assertEquals(0, h.notice.count("im_message")); assertTrue(h.notice.wakes.isEmpty());
            verifyNoInteractions(h.notice.wx, h.oldNotices);
        }
    }

    @Test void durableIdentityConflictRollsBackTheRealProducerAndRetainsTheOriginalIntent() throws Exception {
        try (var h = new Harness()) {
            Long original = h.notice.enqueue("complaint-advance:1", 100L, "original", "original body");
            h.notice.wakes.clear();
            assertThrows(RuntimeException.class, () -> h.service.advance(1L, new BigDecimal("50"), 999L));
            h.assertAdvance(0, "0.00", 0);
            assertEquals(1, h.notice.count("im_notification_intent"));
            assertEquals(1, h.notice.count("committed_official_notice_wx"));
            assertEquals("original", h.db.queryForObject("select title from committed_official_notice_wx where id=?", String.class, original));
            assertEquals("PENDING", h.notice.state(original)); assertTrue(h.notice.wakes.isEmpty());
            assertEquals(0, h.notice.count("im_message")); verifyNoInteractions(h.notice.wx, h.oldNotices);
        }
    }

    @Test void repeatedAdvanceIsRejectedWithoutAnotherCreditIntentOrWake() throws Exception {
        try (var h = new Harness()) {
            assertEquals(1, h.service.advance(1L, new BigDecimal("50"), 999L));
            assertThrows(IllegalStateException.class, () -> h.service.advance(1L, new BigDecimal("50"), 999L));
            h.assertAdvance(1, "50.00", 1);
            assertEquals(1, h.notice.count("im_notification_intent"));
            assertEquals(1, h.notice.count("committed_official_notice_wx")); assertEquals(1, h.notice.wakes.size());
            assertEquals(0, h.notice.count("im_message")); verifyNoInteractions(h.notice.wx, h.oldNotices);
        }
    }

    @Test void twoRealAdvanceTransactionsHaveOneCasWinnerOneCreditAndOneOriginalNotice() throws Exception {
        try (var h = new Harness()) {
            CountDownLatch read = new CountDownLatch(2);
            CoopComplaintMapper mapper = (CoopComplaintMapper) ReflectionTestUtils.getField(h.target, "coopComplaintMapper");
            SqlSessionTemplate session = (SqlSessionTemplate) ReflectionTestUtils.getField(h.legacy, "session");
            doAnswer(call -> {
                CoopComplaint row = session.getMapper(CoopComplaintMapper.class).selectCoopComplaintById(1L);
                read.countDown(); assertTrue(read.await(10, TimeUnit.SECONDS)); return row;
            }).when(mapper).selectCoopComplaintById(1L);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> request = () -> {
                    try { return h.service.advance(1L, new BigDecimal("50"), 999L) == 1; }
                    catch (IllegalStateException rejected) { return false; }
                };
                Future<Boolean> first = pool.submit(request), second = pool.submit(request);
                assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
                h.assertAdvance(1, "50.00", 1);
                assertEquals(1, h.notice.count("im_notification_intent"));
                assertEquals(1, h.notice.count("committed_official_notice_wx")); assertEquals(1, h.notice.wakes.size());
                h.notice.recover(); h.notice.oneMessage(); verify(h.notice.wx).send(any());
            } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        }
    }

    @Test void lostCommitWakeAndTaskAckRecoverWithoutReenteringTheFinancialProducer() throws Exception {
        try (var h = new Harness()) {
            assertEquals(1, h.service.advance(1L, new BigDecimal("50"), 999L));
            h.notice.wakes.clear(); h.notice.recover();
            Long id = h.db.queryForObject("select id from committed_official_notice_wx", Long.class);
            h.notice.freshOwner(h.notice.mapper).deliver(id);
            h.notice.oneMessage(); h.assertAdvance(1, "50.00", 1); verify(h.notice.wx).send(any());
            verifyNoInteractions(h.oldNotices);
        }
    }

    @Test void unknownWxAfterActualAdvanceNeverRepeatsAndCannotUndoTheCommittedBusinessResult() throws Exception {
        try (var h = new Harness()) {
            assertEquals(1, h.service.advance(1L, new BigDecimal("50"), 999L));
            when(h.notice.wx.send(any())).thenReturn(false);
            h.notice.recover();
            Long id = h.db.queryForObject("select id from committed_official_notice_wx", Long.class);
            assertEquals("UNKNOWN", h.notice.state(id));
            h.notice.recover(); h.notice.freshOwner(h.notice.mapper).deliver(id);
            h.notice.oneMessage(); h.assertAdvance(1, "50.00", 1);
            assertEquals("UNKNOWN", h.notice.state(id)); verify(h.notice.wx).send(any());
            verifyNoInteractions(h.oldNotices);
        }
    }

    @Test void actualControllerCloseFreezesTheOriginalRespondentAndActualDeduction() throws Exception {
        try (var h = new Harness()) {
            assertEquals(200, h.controller.close(Map.of("id", 1L, "respondentType", "merchant",
                    "respondentMemberId", 200L, "faultRatio", 100, "deductAmount", "50", "note", "completed")).get("code"));
            h.assertAdvance(3, "0.00", 1);
            assertEquals(new BigDecimal("50.00"), h.db.queryForObject("select balance from ums_member where id=200", BigDecimal.class));
            assertEquals(1, h.notice.count("im_notification_intent")); assertEquals(0, h.notice.count("im_message"));
            assertEquals("complaint-close-respondent:1", h.db.queryForObject("select event_key from committed_official_notice_wx", String.class));
            verifyNoInteractions(h.notice.wx);
            verify(h.oldNotices).submitReporter(eq(1L), eq(100L), eq(999L), eq("投诉工单已处理"),
                    eq("工单#1已关闭，请在投诉记录查看处理说明。"));
            verifyNoMoreInteractions(h.oldNotices);
            h.db.update("update coop_complaint set respondent_member_id=999,deduct_amount=1,handle_note='edited after commit' where id=1");
            h.notice.wakes.clear(); h.notice.recover(); h.assertRespondentNotice();
            assertEquals("投诉工单处理结果\n工单#1已处理，实际扣划 ¥50。请核对账务及处理说明。",
                    h.db.queryForObject("select content from im_message", String.class));
            verify(h.notice.wx).send(argThat(message -> message.memberId().equals(200L)
                    && message.get("sub").equals("工单#1已处理，实际扣划 ¥50。请核对账务及处理说明。")));
        }
    }

    @Test void closeOuterFailureRollsBackTheActualDebitDetailAndRespondentIntent() throws Exception {
        try (var h = new Harness()) {
            assertThrows(IllegalStateException.class, () -> h.notice.tx.executeWithoutResult(status -> {
                assertEquals(1, h.closeComplaint());
                assertEquals(1, h.notice.count("im_notification_intent"));
                assertEquals(1, h.notice.count("committed_official_notice_wx")); assertTrue(h.notice.wakes.isEmpty());
                throw new IllegalStateException("fixture failure after actual close returned");
            }));
            h.assertAdvance(0, "0.00", 0);
            assertEquals(new BigDecimal("100.00"), h.db.queryForObject("select balance from ums_member where id=200", BigDecimal.class));
            assertEquals(0, h.notice.count("im_notification_intent"));
            assertEquals(0, h.notice.count("committed_official_notice_wx"));
            assertEquals(0, h.notice.count("im_message")); assertTrue(h.notice.wakes.isEmpty());
            verifyNoInteractions(h.notice.wx);
        }
    }

    @Test void respondentIntentConflictRollsBackTheActualCloseDebitWithoutReplacingTheOriginalIntent() throws Exception {
        try (var h = new Harness()) {
            Long original = h.notice.enqueue("complaint-close-respondent:1", 200L, "original", "original body");
            h.notice.wakes.clear(); assertThrows(RuntimeException.class, h::closeComplaint);
            h.assertAdvance(0, "0.00", 0);
            assertEquals(new BigDecimal("100.00"), h.db.queryForObject("select balance from ums_member where id=200", BigDecimal.class));
            assertEquals(1, h.notice.count("im_notification_intent"));
            assertEquals(1, h.notice.count("committed_official_notice_wx"));
            assertEquals("original", h.db.queryForObject("select title from committed_official_notice_wx where id=?", String.class, original));
            assertEquals("PENDING", h.notice.state(original)); assertTrue(h.notice.wakes.isEmpty());
            assertEquals(0, h.notice.count("im_message")); verifyNoInteractions(h.notice.wx);
        }
    }

    @Test void closedBusinessAndUnknownRespondentWxCannotRepeatDebitOrExternalAttempt() throws Exception {
        try (var h = new Harness()) {
            assertEquals(1, h.closeComplaint());
            assertThrows(IllegalStateException.class, h::closeComplaint);
            h.assertAdvance(3, "0.00", 1);
            assertEquals(new BigDecimal("50.00"), h.db.queryForObject("select balance from ums_member where id=200", BigDecimal.class));
            when(h.notice.wx.send(any())).thenReturn(false);
            h.notice.wakes.clear(); h.notice.recover();
            Long id = h.db.queryForObject("select id from committed_official_notice_wx", Long.class);
            h.notice.recover(); h.notice.freshOwner(h.notice.mapper).deliver(id);
            h.assertRespondentNotice(); assertEquals("UNKNOWN", h.notice.state(id));
            verify(h.notice.wx).send(any());
            assertEquals(1, h.db.queryForObject("select count(*) from ums_member_balance_detail", Integer.class));
            assertEquals(new BigDecimal("50.00"), h.db.queryForObject("select balance from ums_member where id=200", BigDecimal.class));
        }
    }

    static final class Harness implements AutoCloseable {
        final CoopComplaintCloseMybatisIntegrationTest legacy = new CoopComplaintCloseMybatisIntegrationTest();
        final CommittedOfficialNoticeMySqlFixture notice = new CommittedOfficialNoticeMySqlFixture();
        JdbcTemplate db;
        ICoopComplaintService service;
        CoopComplaintServiceImpl target;
        IPlayerWithdrawalCooldownService cooldown;
        ComplaintCloseReporterNotice oldNotices;
        CoopComplaintController controller;

        Harness() throws Exception {
            for (String key : List.of("CHENGYIN_TEST_MYSQL_PORT", "CHENGYIN_TEST_MYSQL_USER", "CHENGYIN_TEST_MYSQL_PASSWORD"))
                assertNotNull(System.getenv(key), "controlled runner must supply " + key);
            try {
                legacy.database(); legacy.setup();
                DataSourceTransactionManager original = (DataSourceTransactionManager) ReflectionTestUtils.getField(legacy, "transactions");
                notice.attach(original); db = (JdbcTemplate) ReflectionTestUtils.getField(legacy, "db");
                service = (ICoopComplaintService) ReflectionTestUtils.getField(legacy, "service");
                target = AopTestUtils.getUltimateTargetObject(service);
                ReflectionTestUtils.setField(target, "committedOfficialNoticeService", notice.notice);
                cooldown = (IPlayerWithdrawalCooldownService) ReflectionTestUtils.getField(target, "playerWithdrawalCooldownService");
                oldNotices = (ComplaintCloseReporterNotice) ReflectionTestUtils.getField(target, "complaintCloseReporterNotice");
                controller = new CoopComplaintController() { @Override public Long getUserId() { return 999L; } };
                ReflectionTestUtils.setField(controller, "coopComplaintService", service);
            } catch (Exception | Error failure) { close(); throw failure; }
        }

        void assertAdvance(int status, String balance, int details) {
            assertEquals(status, db.queryForObject("select status from coop_complaint where id=1", Integer.class));
            assertEquals(new BigDecimal(balance), db.queryForObject("select balance from ums_member where id=100", BigDecimal.class));
            assertEquals(details, db.queryForObject("select count(*) from ums_member_balance_detail", Integer.class));
        }

        int closeComplaint() {
            return service.close(1L, "merchant", 200L, 100, new BigDecimal("50"), "completed", 999L);
        }

        void assertRespondentNotice() {
            assertEquals(1, notice.count("im_message"));
            assertEquals(200L, db.queryForObject("select receiver_id from im_message", Long.class));
            assertEquals(1, db.queryForObject("select unread_count from im_conversation_member where member_id=200", Integer.class));
            assertEquals("DELIVERED", db.queryForObject("select status from im_notification_intent", String.class));
            assertEquals(db.queryForObject("select id from im_message", Long.class),
                    db.queryForObject("select message_id from im_notification_intent", Long.class));
        }

        @Override public void close() {
            try { notice.close(); }
            finally {
                legacy.cleanup();
                JdbcTemplate admin = (JdbcTemplate) ReflectionTestUtils.getField(legacy, "admin");
                String database = (String) ReflectionTestUtils.getField(legacy, "database");
                if (admin != null && database != null) {
                    assertEquals(0, admin.queryForObject("select count(*) from information_schema.schemata where schema_name=?", Integer.class, database));
                    System.out.println("COMPLAINT_COMMITTED_NOTICE_CLEAN " + database);
                }
            }
        }
    }
}
