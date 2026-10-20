package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.MyBatisExceptionTranslator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F3 证据：商家按章核销 {@code redeemExploreChapterAndRecord} 在 REPEATABLE READ（DEFAULT 隔离）下
 * 与持旧读视图的并发事务能否正确运行。真 MySQL、真实 Mapper XML、每个会话一笔显式提交的真实事务；
 * 核销、权益、通关事实走真实 CmsRegistrationServiceImpl / PerkLedgerServiceImpl / TopicCompletionAwardServiceImpl，
 * 应付、台账、审计、成长、赏金为计次桩（不是财务入账验收）。
 *
 * <p>结论（各用例逐条坐实）：
 * <ul>
 *   <li>同票两次扫码：方法开头依次是活动行、报名行、人工退款单、回执的锁读，锁读不建读视图；
 *       第一条一致性读在拿到票锁之后，所以后到者的读视图晚于先到者提交，能看到它的核销并正确判通关。
 *       负控：若在票锁前先做一次普通读（读视图提前），后到者看不到先到者的核销，两章都核销了却不判通关。</li>
 *   <li>跨路径（打卡等）同会员同主题通关：不受票锁互斥。核销读视图建立后另一路径提交 TOPIC_FINISH，
 *       REPEATABLE READ 下 grantOnce 撞唯一键后的普通读回查看不到它，抛 DuplicateKeyException，整笔核销回滚；
 *       商家重扫成功。READ COMMITTED 对照下同一时序返回「已通关」。</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class RedeemExploreReadViewMySqlIT {
    private static final ExploreItDatabase DATABASE = new ExploreItDatabase("f3_redeem_read_view");
    private static final long REG = 9905001L, TOPIC = 9905002L, ACT = 9905003L, CH1 = 9905011L, CH2 = 9905012L,
            MERCHANT = 9905021L, MEMBER = 9905032L;

    private PooledDataSource dataSource;

    @AfterAll
    static void cleanup() throws Exception {
        DATABASE.drop();
    }

    private SqlSessionFactory factory() throws Exception {
        dataSource = DATABASE.freshDataSource();
        Configuration cfg = new Configuration();
        for (Class<?> type : new Class<?>[] {CmsActivity.class, CmsTopic.class, CmsRegistration.class,
                CmsTopicChapter.class, MerchantChapterOffer.class, RegistrationEntitlement.class, Redemption.class,
                PlayerBadge.class}) {
            cfg.getTypeAliasRegistry().registerAlias(type.getSimpleName(), type);
        }
        cfg.setEnvironment(new Environment("read-view-it", new JdbcTransactionFactory(), dataSource));
        for (String name : new String[] {"CmsTopic", "CmsActivity", "CmsRegistration", "CmsTopicChapter",
                "MerchantChapterOffer", "RegistrationEntitlement", "Redemption", "PlayerBadge"}) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        Path baseline = Paths.get("../sql/base_schema_20260818.sql");
        if (!Files.exists(baseline)) baseline = Paths.get("sql/base_schema_20260818.sql");
        String ddl = new String(Files.readAllBytes(baseline), StandardCharsets.UTF_8);
        int start = ddl.indexOf("CREATE TABLE `player_badge`");
        assertTrue(start >= 0, "基线缺 player_badge（含 uk_member_badge）");
        try (SqlSession s = factory.openSession(true); Statement st = s.getConnection().createStatement()) {
            st.execute(ddl.substring(start, ddl.indexOf(';', start) + 1));
            st.execute("CREATE TABLE IF NOT EXISTS activity_cancel_manual_refund_case(id BIGINT PRIMARY KEY AUTO_INCREMENT,activity_id BIGINT,topic_id BIGINT,parent_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,payer_member_id BIGINT,payer_registration_id BIGINT,case_type VARCHAR(32),status VARCHAR(48),verified_registration_ids_json JSON,unverified_registration_ids_json JSON) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("INSERT INTO cms_topic(id,name,product_type) VALUES(" + TOPIC + ",'read view IT',2)");
            st.execute("INSERT INTO cms_activity(id,member_id,name,topic_id,del_flag,end_date) VALUES(" + ACT + ",9905031,'read view IT'," + TOPIC + ",0,NOW()+INTERVAL 1 DAY)");
            st.execute("INSERT INTO cms_registration(id,registration_no,member_id,owner_type,owner_id,ticket_id,ticket_price,original_amount,payable_amount,total_amount,pay_expire_time,payment_status,registration_status,verification_status,purchase_kind) VALUES(" + REG + ",'READ-VIEW-IT'," + MEMBER + ",2," + ACT + ",9905033,39,39,39,39,NOW()+INTERVAL 1 DAY,2,2,0,3)");
            for (long chapter : new long[] {CH1, CH2}) {
                st.execute("INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,required,terms_mode,recruit_enabled,max_merchant,category_id) VALUES(" + chapter + "," + TOPIC + ",'IT chapter',1,1,'PERK',1,1,18)");
                st.execute("INSERT INTO merchant_chapter_offer(topic_id,chapter_id,merchant_id,perk_id,terms_mode,quota_total,quota_used,status) VALUES(" + TOPIC + "," + chapter + "," + MERCHANT + ",9905034,'PERK',10,0,1)");
                st.execute("INSERT INTO registration_entitlement(registration_id,chapter_id,topic_id,status) VALUES(" + REG + "," + chapter + "," + TOPIC + ",0)");
            }
        }
        return factory;
    }

    /** 一笔会话 = 一笔真实事务；pause 非空时在核销写台账处（票锁、读视图都已建立，发奖之前）停住。 */
    private final class Scan {
        final CmsRegistrationServiceImpl registrations = new CmsRegistrationServiceImpl();
        final PerkLedgerServiceImpl perks = new PerkLedgerServiceImpl();
        final IPlayerProgressionService progression = mock(IPlayerProgressionService.class);
        final CountDownLatch paused = new CountDownLatch(1);

        Scan(SqlSession s, CountDownLatch release) {
            IMerchantPayableService payable = mock(IMerchantPayableService.class);
            CmsRegistrationScanMapper scans = mock(CmsRegistrationScanMapper.class);
            BusinessAuditEventMapper audit = mock(BusinessAuditEventMapper.class);
            ReflectionTestUtils.setField(perks, "topicMapper", s.getMapper(CmsTopicMapper.class));
            ReflectionTestUtils.setField(perks, "chapterMapper", s.getMapper(CmsTopicChapterMapper.class));
            ReflectionTestUtils.setField(perks, "offerMapper", s.getMapper(MerchantChapterOfferMapper.class));
            ReflectionTestUtils.setField(perks, "entitlementMapper", s.getMapper(RegistrationEntitlementMapper.class));
            ReflectionTestUtils.setField(perks, "redemptionMapper", s.getMapper(RedemptionMapper.class));
            ReflectionTestUtils.setField(perks, "cmsRegistrationMapper", s.getMapper(CmsRegistrationMapper.class));
            ReflectionTestUtils.setField(perks, "cmsActivityMapper", s.getMapper(CmsActivityMapper.class));
            ReflectionTestUtils.setField(perks, "merchantPayableService", payable);
            ReflectionTestUtils.setField(perks, "officialEventV2Service", mock(IOfficialEventV2Service.class));
            ReflectionTestUtils.setField(perks, "businessAuditEventMapper", audit);
            ReflectionTestUtils.setField(registrations, "cmsRegistrationMapper", s.getMapper(CmsRegistrationMapper.class));
            ReflectionTestUtils.setField(registrations, "cmsActivityMapper", s.getMapper(CmsActivityMapper.class));
            ReflectionTestUtils.setField(registrations, "perkLedgerService", perks);
            ReflectionTestUtils.setField(registrations, "redemptionMapper", s.getMapper(RedemptionMapper.class));
            ReflectionTestUtils.setField(registrations, "cmsRegistrationScanMapper", scans);
            ReflectionTestUtils.setField(registrations, "merchantReferralBountyService", mock(MerchantReferralBountyService.class));
            ReflectionTestUtils.setField(registrations, "merchantPayableService", payable);
            ReflectionTestUtils.setField(registrations, "businessAuditEventMapper", audit);
            ReflectionTestUtils.setField(registrations, "growthService", mock(IGrowthService.class));
            ReflectionTestUtils.setField(registrations, "topicCompletionAwardService", award(s, progression));
            when(scans.selectCmsRegistrationScanList(any())).thenAnswer(call -> {
                if (release != null) {
                    paused.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS), "测试未放行停住的核销");
                }
                return Collections.emptyList();
            });
            when(audit.insertBusinessAuditEvent(any())).thenReturn(1);
        }

        ExploreChapterRedemptionResult redeem(long chapter) {
            // 等价于 controller 在事务外取得的票定位快照；core 自己锁读复核。
            CmsRegistration reg = new CmsRegistration();
            reg.setId(REG);
            reg.setMemberId(MEMBER);
            reg.setOwnerType(2L);
            reg.setOwnerId(ACT);
            reg.setPaymentStatus(2);
            reg.setRegistrationStatus(2);
            return registrations.redeemExploreChapterAndRecord(reg, chapter, TOPIC, MERCHANT, MERCHANT, null);
        }
    }

    /** 真实发奖实现，勋章表走真实 XML；生产里 SqlSessionTemplate 负责把 1062 翻译成 DuplicateKeyException，这里同样翻译。 */
    private ITopicCompletionAwardService award(SqlSession s, IPlayerProgressionService progression) {
        PlayerBadgeMapper real = s.getMapper(PlayerBadgeMapper.class);
        MyBatisExceptionTranslator translator = new MyBatisExceptionTranslator(dataSource, true);
        PlayerBadgeMapper badges = (PlayerBadgeMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PlayerBadgeMapper.class}, (proxy, method, args) -> {
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof PersistenceException) {
                            RuntimeException translated = translator.translateExceptionIfPossible((PersistenceException) cause);
                            if (translated != null) throw translated;
                        }
                        throw cause;
                    }
                });
        when(progression.onTopicComplete(anyLong(), anyLong())).thenReturn(Collections.emptyList());
        TopicCompletionAwardServiceImpl target = new TopicCompletionAwardServiceImpl();
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("read view IT");
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        IXpBudgetService budget = mock(IXpBudgetService.class);
        when(budget.computeBudget(anyLong())).thenReturn(Collections.<String, Object>singletonMap("remain", 0));
        ReflectionTestUtils.setField(target, "topicService", topics);
        ReflectionTestUtils.setField(target, "playerBadgeMapper", badges);
        ReflectionTestUtils.setField(target, "playerProgressionService", progression);
        ReflectionTestUtils.setField(target, "pointsAwardService", mock(IPointsAwardService.class));
        ReflectionTestUtils.setField(target, "officialEventV2Service", mock(IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(target, "growthMissionProgressMapper", mock(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(target, "xpBudgetService", budget);
        ReflectionTestUtils.setField(target, "xpAwardMapper", mock(TopicCompletionXpAwardMapper.class));
        return target;
    }

    private static SqlSession open(SqlSessionFactory factory, int isolation) throws Exception {
        SqlSession s = factory.openSession(false);
        s.getConnection().setTransactionIsolation(isolation);
        return s;
    }

    private long scalar(SqlSessionFactory f, String query) throws Exception {
        try (SqlSession s = f.openSession(true); Statement st = s.getConnection().createStatement();
             ResultSet r = st.executeQuery(query)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    private void awaitLockWait(SqlSessionFactory f, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (scalar(f, "select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l"
                    + " on l.engine_lock_id = w.requesting_engine_lock_id where l.object_schema = database()"
                    + " and l.object_name = '" + table + "'") > 0) return;
            Thread.sleep(10);
        }
        fail("后到事务未在 " + table + " 上排队等锁");
    }

    private long markers(SqlSessionFactory f) throws Exception {
        return scalar(f, "select count(*) from player_badge where member_id=" + MEMBER
                + " and badge_code='TOPIC_FINISH_" + TOPIC + "' and source_id=" + TOPIC);
    }

    private long activeRedemptions(SqlSessionFactory f) throws Exception {
        return scalar(f, "select count(*) from redemption where registration_id=" + REG + " and status='ACTIVE'");
    }

    /** 两章分由两笔事务同时核销；earlyReadView=true 时后到者在调用前先做一次普通读（读视图提前到票锁之前）。 */
    private Scan[] sameTicketTwoChapters(SqlSessionFactory f, boolean earlyReadView) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try (SqlSession s1 = open(f, Connection.TRANSACTION_REPEATABLE_READ);
             SqlSession s2 = open(f, Connection.TRANSACTION_REPEATABLE_READ)) {
            Scan first = new Scan(s1, releaseFirst), second = new Scan(s2, null);
            Future<ExploreChapterRedemptionResult> one = workers.submit(() -> {
                ExploreChapterRedemptionResult r = first.redeem(CH1);
                s1.commit();
                return r;
            });
            assertTrue(first.paused.await(10, TimeUnit.SECONDS), "先到者未进入核销");
            Future<ExploreChapterRedemptionResult> two = workers.submit(() -> {
                if (earlyReadView) {
                    try (Statement st = s2.getConnection().createStatement();
                         ResultSet r = st.executeQuery("select count(*) from redemption where registration_id=" + REG)) {
                        assertTrue(r.next());
                        assertEquals(0, r.getLong(1), "在先到者提交前建立读视图");
                    }
                }
                ExploreChapterRedemptionResult r = second.redeem(CH2);
                s2.commit();
                return r;
            });
            awaitLockWait(f, "cms_activity");
            assertFalse(two.isDone(), "票锁互斥：先到者提交前后到者不得越过活动行锁");
            releaseFirst.countDown();
            assertFalse(one.get(10, TimeUnit.SECONDS).isReplayed());
            assertFalse(two.get(10, TimeUnit.SECONDS).isReplayed());
            return new Scan[] {first, second};
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void sameTicketLaterScanOpensReadViewOnlyAfterTicketLocksAndSeesFirstRedemption() throws Exception {
        SqlSessionFactory f = factory();
        Scan[] scans = sameTicketTwoChapters(f, false);
        assertEquals(2, activeRedemptions(f));
        assertEquals(1, markers(f), "后到者的一致性读晚于先到者提交，两章齐 ⇒ 判通关");
        verify(scans[0].progression, never()).onTopicComplete(anyLong(), anyLong());
        verify(scans[1].progression).onTopicComplete(MEMBER, TOPIC);
    }

    @Test
    void negativeControlReadViewBeforeTicketLocksLosesCompletion() throws Exception {
        SqlSessionFactory f = factory();
        Scan[] scans = sameTicketTwoChapters(f, true);
        assertEquals(2, activeRedemptions(f), "两章都核销成功");
        assertEquals(0, markers(f), "负控：读视图早于先到者提交 ⇒ 后到者只数到自己那章，通关丢失");
        verify(scans[0].progression, never()).onTopicComplete(anyLong(), anyLong());
        verify(scans[1].progression, never()).onTopicComplete(anyLong(), anyLong());
    }

    /** CH1 预先核销；CH2 核销停在读视图建立之后、发奖之前，期间另一路径（打卡等）为同会员同主题提交通关事实。 */
    private Future<ExploreChapterRedemptionResult> crossPathCompletionDuringScan(SqlSessionFactory f, int isolation)
            throws Exception {
        try (SqlSession s0 = open(f, Connection.TRANSACTION_REPEATABLE_READ)) {
            new Scan(s0, null).redeem(CH1);
            s0.commit();
        }
        assertEquals(0, markers(f));
        ExecutorService workers = Executors.newFixedThreadPool(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<SqlSession> session = new CompletableFuture<>();
            CompletableFuture<Scan> scanRef = new CompletableFuture<>();
            Future<ExploreChapterRedemptionResult> scan = workers.submit(() -> {
                try (SqlSession s2 = open(f, isolation)) {
                    session.complete(s2);
                    Scan second = new Scan(s2, release);
                    scanRef.complete(second);
                    ExploreChapterRedemptionResult r = second.redeem(CH2);
                    s2.commit();
                    return r;
                }
            });
            assertTrue(scanRef.get(10, TimeUnit.SECONDS).paused.await(10, TimeUnit.SECONDS), "核销未停在发奖前");
            try (SqlSession s3 = open(f, Connection.TRANSACTION_READ_COMMITTED)) {
                TopicCompletionAwardVO other = award(s3, mock(IPlayerProgressionService.class))
                        .grantOnce(MEMBER, TOPIC, 0L, null);
                assertTrue(other.isFirstCompletion(), "另一路径先确立通关事实");
                s3.commit();
            }
            assertEquals(1, markers(f));
            release.countDown();
            try {
                scan.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException expectedUnderRepeatableRead) {
                // 由调用方断言
            }
            return scan;
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void crossPathCompletionAfterReadViewFailsScanUnderRepeatableReadAndRescanRecovers() throws Exception {
        SqlSessionFactory f = factory();
        Future<ExploreChapterRedemptionResult> scan = crossPathCompletionDuringScan(f, Connection.TRANSACTION_REPEATABLE_READ);
        ExecutionException failure = assertThrows(ExecutionException.class, scan::get);
        assertTrue(failure.getCause() instanceof DuplicateKeyException, String.valueOf(failure.getCause()));
        assertEquals(1, activeRedemptions(f), "撞唯一键后普通读回查看不到对方提交 ⇒ 抛出，CH2 核销整笔回滚");
        assertEquals(0, scalar(f, "select status from registration_entitlement where registration_id=" + REG
                + " and chapter_id=" + CH2));
        assertEquals(1, markers(f));
        try (SqlSession again = open(f, Connection.TRANSACTION_REPEATABLE_READ)) {
            Scan rescan = new Scan(again, null);
            assertFalse(rescan.redeem(CH2).isReplayed());
            again.commit();
            verify(rescan.progression, never()).onTopicComplete(anyLong(), anyLong());
        }
        assertEquals(2, activeRedemptions(f), "商家重扫：新读视图看得到通关事实，按已通关成立");
        assertEquals(1, markers(f));
    }

    @Test
    void readCommittedControlConfirmsExistingCompletionInSameInterleaving() throws Exception {
        SqlSessionFactory f = factory();
        Future<ExploreChapterRedemptionResult> scan = crossPathCompletionDuringScan(f, Connection.TRANSACTION_READ_COMMITTED);
        assertFalse(scan.get().isReplayed(), "READ COMMITTED 下回查看得到对方提交 ⇒ 按已通关成立");
        assertEquals(2, activeRedemptions(f));
        assertEquals(1, markers(f));
    }
}
