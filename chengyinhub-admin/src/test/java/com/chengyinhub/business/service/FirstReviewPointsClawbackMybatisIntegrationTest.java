package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService;
import com.chengyinhub.business.service.impl.RedemptionRewardClawbackService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FX-10(9-18 拍板「收回」):退款后收回靠这张票拿到的首评积分(event_type=4),真库验证。
 * 「还参与过没有」看 (已支付且 registration_status=2) 或 已核销;主题认本主题票 + 其下场次票。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstReviewPointsClawbackMybatisIntegrationTest {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("first_review_clawback_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private FirstReviewPointsClawbackService service;
    private UmsMemberPointsDetailMapper pointsDetails;

    @BeforeAll void setup() throws Exception {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        admin.execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
        admin.execute("CREATE DATABASE " + mysql.databaseName() + " CHARACTER SET utf8mb4");
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : new String[]{"ums_comment", "ums_member_points_detail"}) {
            int start = base.indexOf("CREATE TABLE `" + table + "` (");
            jdbc.execute(base.substring(start, base.indexOf(";", start)));
        }
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, point BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT)");
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT, owner_type BIGINT,"
                + " owner_id BIGINT, payment_status INT, registration_status INT, verification_status INT,"
                + " update_marker INT DEFAULT 0)");
        Configuration config = new Configuration(new Environment("clawback", new SpringManagedTransactionFactory(), dataSource));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> type : new Class<?>[]{UmsCommentMapper.class, UmsMemberMapper.class, UmsMemberPointsDetailMapper.class}) {
            config.addMapper(type);
            String resource = "mapper/business/" + type.getSimpleName() + ".xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        UmsMemberMapper members = mock(UmsMemberMapper.class, org.mockito.AdditionalAnswers.delegatesTo(
                session.getMapper(UmsMemberMapper.class)));
        // ums_member 只建 id/point 两列:selectUmsMemberById 的全列查询换成只读 point。
        org.mockito.Mockito.doAnswer(inv -> {
            com.chengyinhub.business.domain.UmsMember m = new com.chengyinhub.business.domain.UmsMember();
            m.setId(inv.getArgument(0));
            m.setPoint(jdbc.queryForObject("SELECT point FROM ums_member WHERE id=?", Long.class, (Object) inv.getArgument(0)));
            return m;
        }).when(members).selectUmsMemberById(org.mockito.ArgumentMatchers.anyLong());
        RedemptionRewardClawbackService clawback = new RedemptionRewardClawbackService();
        pointsDetails = mock(UmsMemberPointsDetailMapper.class, org.mockito.AdditionalAnswers.delegatesTo(
                session.getMapper(UmsMemberPointsDetailMapper.class)));
        ReflectionTestUtils.setField(clawback, "pointsDetailMapper", pointsDetails);
        ReflectionTestUtils.setField(clawback, "umsMemberMapper", members);
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            CmsActivity a = new CmsActivity();
            a.setId(inv.getArgument(0));
            a.setTopicId(jdbc.queryForObject("SELECT topic_id FROM cms_activity WHERE id=?", Long.class, (Object) inv.getArgument(0)));
            return a;
        });
        service = new FirstReviewPointsClawbackService();
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        ReflectionTestUtils.setField(service, "umsCommentMapper", session.getMapper(UmsCommentMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "clawback", clawback);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        ReflectionTestUtils.setField(service, "transactionManager", manager);
        tx = new TransactionTemplate(manager);
    }

    @AfterAll void cleanup() {
        if (dataSource != null) new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE IF EXISTS " + mysql.databaseName());
    }

    @BeforeEach void reset() {
        for (String t : new String[]{"ums_comment", "ums_member_points_detail", "ums_member", "cms_activity", "cms_registration"}) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO ums_member VALUES (1, 100)");
        jdbc.update("INSERT INTO cms_activity VALUES (20, 7)");
        org.mockito.Mockito.reset(pointsDetails);
    }

    /** 发一条评论 + 它的首评积分(照 CommentPublicationService 的写法:event_type=4、event_id=评论 id、change_type=1)。 */
    private void reviewWithReward(long commentId, long ownerType, long ownerId, long points) {
        jdbc.update("INSERT INTO ums_comment (id, member_id, owner_type, owner_id, contents, status, del_flag) VALUES (?,?,?,?,?,1,0)",
                commentId, 1L, ownerType, ownerId, "好玩");
        jdbc.update("INSERT INTO ums_member_points_detail (member_id, current_points, after_points, change_points, change_reason,"
                + " event_type, event_id, change_type, del_flag, create_time) VALUES (1, 0, ?, ?, '活动评价/投票', 4, ?, 1, 0, NOW())",
                points, points, commentId);
    }

    /** 退款后的报名:registration_status=3, payment_status=4。 */
    private CmsRegistration refunded(long id, long ownerType, long ownerId) {
        jdbc.update("INSERT INTO cms_registration VALUES (?, 1, ?, ?, 4, 3, 0, 0)", id, ownerType, ownerId);
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(1L);
        r.setOwnerType(ownerType);
        r.setOwnerId(ownerId);
        return r;
    }

    private void refund(CmsRegistration r) {
        tx.executeWithoutResult(s -> service.onRegistrationRefunded(r));
    }

    private long points() { return jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Long.class); }

    private long clawedTotal() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(change_points),0) FROM ums_member_points_detail"
                + " WHERE member_id=1 AND event_type=4 AND change_type=2", Long.class);
    }

    @Test void refundOfTheOnlyTicketClawsBackTheFirstReviewPoints() {
        reviewWithReward(501, 1, 7, 30);
        refund(refunded(9001, 1, 7));

        assertEquals(70, points());
        assertEquals(30, clawedTotal(), "照积分流水写法记一笔负向(change_type=2)");
        assertEquals(0, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail"
                + " WHERE event_type=4 AND event_id=501 AND change_type=1", Long.class), "原发放行摘出判重键");
    }

    @Test void repeatedRefundCallbacksClawBackOnlyOnce() {
        reviewWithReward(501, 1, 7, 30);
        CmsRegistration r = refunded(9001, 1, 7);
        refund(r);
        refund(r);

        assertEquals(70, points());
        assertEquals(30, clawedTotal());
    }

    @Test void concurrentRefundsOfTwoTicketsClawBackOnlyOnce() throws Exception {
        reviewWithReward(501, 1, 7, 30);
        CmsRegistration a = refunded(9001, 1, 7);
        CmsRegistration b = refunded(9002, 1, 7);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> ra = () -> { start.await(); refund(a); return null; };
            Callable<Void> rb = () -> { start.await(); refund(b); return null; };
            Future<Void> fa = pool.submit(ra);
            Future<Void> fb = pool.submit(rb);
            start.countDown();
            fa.get();
            fb.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(70, points(), "两张票并发退款,首评积分只扣一次");
        assertEquals(30, clawedTotal());
    }

    @Test void anotherStillValidTicketKeepsThePoints() {
        reviewWithReward(501, 1, 7, 30);
        jdbc.update("INSERT INTO cms_registration VALUES (9002, 1, 1, 7, 2, 2, 0, 0)");   // 同主题另一张没退的票
        refund(refunded(9001, 1, 7));

        assertEquals(100, points(), "还参与着就不收");
        assertEquals(0, clawedTotal());
    }

    @Test void cancelledButStillPaidRegistrationDoesNotCountAsParticipation() {
        reviewWithReward(501, 1, 7, 30);
        jdbc.update("INSERT INTO cms_registration VALUES (9002, 1, 1, 7, 2, 3, 0, 0)");   // 取消中间态:已支付但报名已取消
        refund(refunded(9001, 1, 7));

        assertEquals(70, points(), "registration_status=3 的报名不算参与");
    }

    @Test void activityTicketRefundClawsBackActivityAndParentTopicReviews() {
        reviewWithReward(601, 2, 20, 30);
        reviewWithReward(602, 1, 7, 30);
        refund(refunded(9003, 2, 20));

        assertEquals(40, points());
        assertEquals(60, clawedTotal());
    }

    @Test void topicTicketStillValidProtectsTopicReviewButNotActivityReview() {
        reviewWithReward(601, 2, 20, 30);
        reviewWithReward(602, 1, 7, 30);
        jdbc.update("INSERT INTO cms_registration VALUES (9004, 1, 1, 7, 2, 2, 0, 0)");   // 主题票还在
        refund(refunded(9003, 2, 20));

        assertEquals(70, points(), "只收场次评价那 30");
    }

    @Test void spentPointsAreClawedBackOnlyDownToZero() {
        reviewWithReward(501, 1, 7, 30);
        jdbc.update("UPDATE ums_member SET point = 10 WHERE id = 1");   // 首评 30 分已花掉 20
        refund(refunded(9001, 1, 7));

        assertEquals(0, points(), "扣到 0 为止,不扣成负数");
        assertEquals(10, clawedTotal(), "流水记实际扣的数");
    }

    /**
     * 总控 9-18「退款不因积分回收失败而回滚」:回收写负向流水时炸了 ⇒ 回收整段回滚到 savepoint
     * (分没扣、判重键没摘,下次还能收),同一事务里退款侧的写入照常提交。
     */
    @Test void clawbackFailureRollsBackOnlyTheClawbackNotTheRefund() {
        reviewWithReward(501, 1, 7, 30);
        CmsRegistration r = refunded(9001, 1, 7);
        org.mockito.Mockito.doThrow(new IllegalStateException("模拟流水写入失败"))
                .when(pointsDetails).insertUmsMemberPointsDetail(org.mockito.ArgumentMatchers.any());

        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE cms_registration SET update_marker = 1 WHERE id = 9001");   // 退款侧的写入
            service.onRegistrationRefunded(r);
        });

        assertEquals(1, (long) jdbc.queryForObject("SELECT update_marker FROM cms_registration WHERE id=9001", Long.class),
                "退款事务照常提交");
        assertEquals(100, points(), "回收的扣分随 savepoint 回滚");
        assertEquals(1, (long) jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail"
                + " WHERE event_type=4 AND event_id=501 AND change_type=1", Long.class), "判重键没被摘,之后还能收");
    }

    /** 总控 9-18 口径「(已支付且 registration_status=2) 或 已核销」:核销过再退款(③探索票运营人工退)算参与过,不收。 */
    @Test void verifiedThenRefundedTicketStillCountsAsParticipation() {
        reviewWithReward(501, 1, 7, 30);
        CmsRegistration r = refunded(9001, 1, 7);
        jdbc.update("UPDATE cms_registration SET verification_status = 1 WHERE id = 9001");
        refund(r);

        assertEquals(100, points(), "人真到过,首评积分不收");
        assertEquals(0, clawedTotal());
    }
}
