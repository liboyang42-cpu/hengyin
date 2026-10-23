package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.impl.GroupCodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AC1 · P0 核销链(扫码整单核销 + 团码)在真 MySQL 8 上的后端半边。
 *
 * <p>整单核销的唯一守卫是 {@code CmsRegistrationMapper.verifyIfNotVerified} 的条件 UPDATE(当前读),
 * 撤销是 {@code clearVerification};这里用生产 mapper XML + base schema 原样 DDL + 原 migration,
 * 两名店员用两条独立连接在屏障后同时打同一张票。团码走 {@link GroupCodeServiceImpl#redeem} 真事务代理,
 * 台账 {@code coop_group_verify} 的唯一键与审计表都来自仓库 DDL。
 *
 * <p>不覆盖:签名码在真机上的出码/扫码、小程序 UI、HTTP 鉴权链(Mockito 与 chain acceptance 另有覆盖)。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0RedemptionChainMySqlIT {
    private static final String SUITE = "p0_redemption";
    private static final String SECRET = "ac1-it-dyncode-secret";
    private static final long TOPIC = 501L;
    private static final long ACTIVITY = 601L;
    private static final long CLUB = 71L;
    private static final long LEADER = 7001L;
    private static final long MERCHANT = 8001L;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate db;
    private CmsRegistrationMapper registrations;
    private IGroupCodeService groupCodes;
    private ICmsActivityService activities;

    @BeforeAll
    void setUp() throws Exception {
        dataSource = P0AcceptanceSchema.dataSource(SUITE);
        db = new JdbcTemplate(dataSource);
        P0AcceptanceSchema.baseTables(db, "cms_registration", "redemption", "coop_group_verify");
        P0AcceptanceSchema.migrations(dataSource,
                "migration_event_cancel_manual_refund_case_20260826.sql",
                "migration_manual_refund_case_topic_20260918.sql",
                "migration_partial_chapter_aftercare_20261002.sql");
        P0AcceptanceSchema.createTableFromMigration(db, "migration_business_audit_event_20260823.sql");
        // migration_approval_governance_20260828.sql 的同一条 ALTER(该文件还含 DELIMITER 触发器与菜单数据,不能整文件执行)。
        db.execute("ALTER TABLE business_audit_event"
                + " ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER owner_member_id,"
                + " MODIFY COLUMN request_id VARCHAR(64) NOT NULL");

        SqlSessionTemplate sql = P0AcceptanceSchema.sql(dataSource,
                "CmsRegistrationMapper", "CoopGroupVerifyMapper", "BusinessAuditEventMapper");
        registrations = sql.getMapper(CmsRegistrationMapper.class);

        activities = mock(ICmsActivityService.class);
        CoopOrderMapper coopOrders = mock(CoopOrderMapper.class);
        CoopOrder merchantOrder = new CoopOrder();
        merchantOrder.setId(901L);
        merchantOrder.setToType("merchant");
        merchantOrder.setToId(MERCHANT);
        CoopOrder clubOrder = new CoopOrder();
        clubOrder.setId(902L);
        clubOrder.setToType("club");
        clubOrder.setToId(CLUB);
        when(coopOrders.selectEffectiveOrdersByTopicId(TOPIC)).thenReturn(List.of(merchantOrder, clubOrder));
        GroupCodeServiceImpl target = new GroupCodeServiceImpl(activities, mock(IClubAccessService.class), coopOrders,
                sql.getMapper(CoopGroupVerifyMapper.class), mock(IQrcodeService.class), SECRET);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", sql.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(target, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(IGroupCodeService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        groupCodes = (IGroupCodeService) proxy.getProxy();
    }

    @BeforeEach
    void clear() {
        db.update("delete from redemption");
        db.update("delete from cms_registration");
        db.update("delete from activity_cancel_manual_refund_case");
        db.update("delete from coop_group_verify");
        db.update("delete from business_audit_event");
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(null));
    }

    @AfterAll
    void dropSchema() {
        if (db != null) {
            P0AcceptanceSchema.drop(db, SUITE);
        }
    }

    @Test
    void legalTicketVerifiesOnceWhileWrongCancelledUnpaidAndRefundedTicketsStayUntouched() {
        ticket(1L, 2, 2);   // 合法:已付 + 报名成功
        ticket(2L, 2, 3);   // 已取消
        ticket(3L, 0, 1);   // 未支付
        ticket(4L, 4, 2);   // 已退款
        String untouchedBefore = snapshot("id in (2,3,4)");

        assertEquals(1, registrations.verifyIfNotVerified(1L, 11L), "合法票必须核销成功");
        assertEquals(0, registrations.verifyIfNotVerified(1L, 12L), "同一张票第二次核销必须被条件 UPDATE 挡住");
        assertEquals(0, registrations.verifyIfNotVerified(999L, 11L), "不存在的码(错误单号)不得写任何行");
        assertEquals(0, registrations.verifyIfNotVerified(2L, 11L), "已取消订单不得核销");
        assertEquals(0, registrations.verifyIfNotVerified(3L, 11L), "未支付订单不得核销");
        assertEquals(0, registrations.verifyIfNotVerified(4L, 11L), "已退款订单不得核销");

        assertEquals(Map.of("verification_status", 1, "verifier_id", 11L),
                db.queryForMap("select verification_status, verifier_id from cms_registration where id=1"));
        assertEquals(untouchedBefore, snapshot("id in (2,3,4)"), "被拒的票一列都不能变");
    }

    @Test
    void twoStaffRacingTheSameTicketProduceExactlyOneVerification() throws Exception {
        int rounds = 20;
        for (long id = 100; id < 100 + rounds; id++) {
            ticket(id, 2, 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (long id = 100; id < 100 + rounds; id++) {
                long ticketId = id;
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<Integer>> results = new ArrayList<>();
                for (long staff : new long[] {21L, 22L}) {
                    results.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return registrations.verifyIfNotVerified(ticketId, staff);
                    }));
                }
                int first = results.get(0).get(30, TimeUnit.SECONDS);
                int second = results.get(1).get(30, TimeUnit.SECONDS);
                assertEquals(1, first + second, "两名店员同扫一张票必须恰好一人成功,票 " + ticketId);
                long winner = first == 1 ? 21L : 22L;
                assertEquals(winner, db.queryForObject(
                        "select verifier_id from cms_registration where id=?", Long.class, ticketId),
                        "落库的核销人必须是返回成功的那名店员");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(rounds, db.queryForObject(
                "select count(*) from cms_registration where id>=100 and verification_status=1", Integer.class));
    }

    @Test
    void reversedTicketClearsExactlyOnceAndCanThenBeVerifiedByOnlyOneStaff() throws Exception {
        ticket(7L, 2, 2);
        assertEquals(1, registrations.verifyIfNotVerified(7L, 31L));
        db.update("insert into redemption(registration_id,chapter_id,topic_id,merchant_id,operator_id,status,"
                + "idempotency_key,create_time) values (7,null,?,?,31,'ACTIVE','redeem:7:ORDER:8001',now())", TOPIC, MERCHANT);

        assertEquals(0, registrations.clearVerification(7L), "仍有 ACTIVE 核销流水时不得清核销位");
        assertEquals(1, db.queryForObject("select verification_status from cms_registration where id=7", Integer.class));

        db.update("update redemption set status='REVERSED', reversed_at=now(), reverse_reason='误扫' where registration_id=7");
        assertEquals(1, registrations.clearVerification(7L), "流水已撤销后必须清回未核销");
        assertEquals(0, registrations.clearVerification(7L), "二次撤销不得再写");
        assertEquals(Map.of("verification_status", 0),
                db.queryForMap("select verification_status from cms_registration where id=7 and verifier_id is null"
                        + " and verification_time is null"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return registrations.verifyIfNotVerified(7L, 32L); });
            Future<Integer> b = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return registrations.verifyIfNotVerified(7L, 33L); });
            assertEquals(1, a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS), "撤销后重新核销也只能一人成功");
        } finally {
            pool.shutdownNow();
        }
        // 同一订单的撤销流水保留(不可变事件),生效唯一键已释放。
        assertEquals(1, db.queryForObject("select count(*) from redemption where registration_id=7 and status='REVERSED'", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from redemption where active_key is not null", Integer.class));
    }

    @Test
    void manualRefundCaseFreezesTheTicketInTheSameAtomicUpdate() {
        ticket(8L, 2, 2);
        db.update("update cms_registration set owner_type=2, owner_id=?, parent_order_no='P-8' where id=8", ACTIVITY);
        db.update("insert into activity_cancel_manual_refund_case(activity_id,owner_member_id,actor_member_id,"
                + "parent_order_no,payer_registration_id,payer_member_id,paid_cash_amount_fen,"
                + "verified_registration_ids_json,unverified_registration_ids_json)"
                + " values (?,7001,7001,'P-8',8,?,6800,'[9]','[8]')", ACTIVITY, 3008L);
        assertEquals(0, registrations.verifyIfNotVerified(8L, 41L), "已进入人工退款(取消售后)的票不得核销");
        db.update("update activity_cancel_manual_refund_case set status='NO_REFUND', version=1,"
                + " assessed_refund_amount_fen=0, assessment_note='AC1 不退款', assessed_by_user_id=1,"
                + " assessment_request_id='ac1-assess-8', assessed_at=now(3)");
        assertEquals(1, registrations.verifyIfNotVerified(8L, 41L), "工单判为不退款后才可核销");
    }

    @Test
    void groupCodeLegalWrongCancelledAndTwoStaffRaceLeaveExactlyOneLedgerRowAndAudit() throws Exception {
        String code = groupCode(LEADER);

        String forged = code.substring(0, code.length() - 2) + (code.endsWith("00") ? "11" : "00");
        AjaxResult wrong = groupCodes.redeem(forged, MERCHANT, 8101L, "MERCHANT_STAFF");
        assertTrue(wrong.isError(), "伪造/错码必须拒绝");
        assertEquals("签名不匹配", wrong.get(AjaxResult.MSG_TAG));
        assertTrue(groupCodes.redeem(VerifyDynCodeUtil.issue(TOPIC, "group_v2_" + ACTIVITY + "_" + LEADER,
                System.currentTimeMillis() - 600_000L, 300_000L, 1L, SECRET), MERCHANT, 8101L, "MERCHANT_STAFF").isError(),
                "过期团码必须拒绝");
        assertTrue(groupCodes.redeem(code, 8999L, 8999L, "MERCHANT_OWNER").isError(), "无合作单的商家不得核销");
        assertLedger(0, 0);

        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(new Date()));
        AjaxResult cancelled = groupCodes.redeem(code, MERCHANT, 8101L, "MERCHANT_STAFF");
        assertTrue(cancelled.isError(), "已取消场次的团码必须拒绝");
        assertEquals("团码场次无效", cancelled.get(AjaxResult.MSG_TAG));
        assertLedger(0, 0);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity(null));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AjaxResult>> results = new ArrayList<>();
            for (long staff : new long[] {8101L, 8102L}) {
                Callable<AjaxResult> call = () -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return groupCodes.redeem(code, MERCHANT, staff, "MERCHANT_STAFF");
                };
                results.add(pool.submit(call));
            }
            int fresh = 0;
            int replay = 0;
            for (Future<AjaxResult> result : results) {
                AjaxResult body = result.get(30, TimeUnit.SECONDS);
                assertTrue(body.isSuccess(), String.valueOf(body));
                if (Boolean.TRUE.equals(((Map<?, ?>) body.get(AjaxResult.DATA_TAG)).get("alreadyVerified"))) {
                    replay++;
                } else {
                    fresh++;
                }
            }
            assertEquals(1, fresh, "两名店员同扫一个团码只能有一人记为首次接待");
            assertEquals(1, replay);
        } finally {
            pool.shutdownNow();
        }
        assertLedger(1, 1);
        assertEquals(Map.of("activity_id", ACTIVITY, "merchant_member_id", MERCHANT, "leader_member_id", LEADER,
                        "coop_order_id", 901L),
                db.queryForMap("select activity_id, merchant_member_id, leader_member_id, coop_order_id from coop_group_verify"));

        AjaxResult again = groupCodes.redeem(groupCode(LEADER), MERCHANT, 8103L, "MERCHANT_STAFF");
        assertTrue(again.isSuccess());
        assertEquals("已记录过", again.get(AjaxResult.MSG_TAG), "同场次同商家重新出码也只能回放");
        assertLedger(1, 1);
    }

    private void assertLedger(int verifies, int audits) {
        assertEquals(verifies, db.queryForObject("select count(*) from coop_group_verify", Integer.class));
        assertEquals(audits, db.queryForObject(
                "select count(*) from business_audit_event where action='GROUP_CODE_REDEEM'", Integer.class));
    }

    private String groupCode(long leader) {
        return VerifyDynCodeUtil.issue(TOPIC, "group_v2_" + ACTIVITY + "_" + leader,
                System.currentTimeMillis(), 300_000L, System.nanoTime(), SECRET);
    }

    private static CmsActivity activity(Date cancelTime) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setClubId(CLUB);
        activity.setDelFlag(0);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setCancelTime(cancelTime);
        return activity;
    }

    private void ticket(long id, int paymentStatus, int registrationStatus) {
        db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                        + "original_amount,total_amount,payable_amount,payment_status,registration_status,pay_expire_time,"
                        + "verification_status,create_time) values (?,?,1,?,1,'68.00',?,'68.00','68.00','68.00',?,?,"
                        + "now()+interval 1 day,0,now())",
                id, "AC1-" + id, TOPIC, 3000L + id, paymentStatus, registrationStatus);
    }

    private String snapshot(String where) {
        return db.queryForList("select id,payment_status,registration_status,verification_status,verifier_id,"
                + "verification_time from cms_registration where " + where + " order by id").toString();
    }
}
