package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamMember;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.io.InputStream;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real mapper and service transactions; only unrelated activity/content/IM services are stubbed. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayTeamMybatisIntegrationTest {
    private final MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment("play_team_it");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private IPlayTeamService service;
    private PlayTeamMapper mapper;
    private CmsActivity activity;

    @BeforeAll void setup() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        dataSource = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260820_play_team_core.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260822_play_team_invite_code.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("sql/migration_20260915_play_team_public_apply.sql"));
        }
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        int start = base.indexOf("CREATE TABLE `cms_registration` (");
        jdbc.execute(base.substring(start, base.indexOf(";", start)));
        int auditStart = base.indexOf("CREATE TABLE `audit_task` (");
        jdbc.execute(base.substring(auditStart, base.indexOf(";", auditStart)));
        jdbc.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, name VARCHAR(100), start_date DATETIME, del_flag INT)");
        jdbc.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, nickname VARCHAR(40), avatar VARCHAR(255))");
        Configuration configuration = new Configuration(new Environment("play-team", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(PlayTeamMapper.class);
        String resource = "mapper/business/PlayTeamMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration)).getMapper(PlayTeamMapper.class);
        PlayTeamServiceImpl target = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(target, "teamMapper", mapper);
        ReflectionTestUtils.setField(target, "blockMapper", mock(ImBlockMapper.class));
        ReflectionTestUtils.setField(target, "imService", mock(IImService.class));
        ReflectionTestUtils.setField(target, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        activity = new CmsActivity();
        activity.setId(10L); activity.setTopicId(20L); activity.setStartDate(new Date(System.currentTimeMillis() + 86400000));
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        when(activities.selectCmsActivityById(10L)).thenReturn(activity);
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        CmsTopic topic = new CmsTopic(); topic.setTeamMode(1); topic.setTeamMaxMembers(4);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicById(20L)).thenReturn(topic);
        ReflectionTestUtils.setField(target, "cmsTopicMapper", topics);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        service = (IPlayTeamService) proxy.getProxy();
    }

    @AfterAll void cleanup() {
        if (dataSource != null) new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
    }

    @BeforeEach void reset() {
        activity.setStartDate(new Date(System.currentTimeMillis() + 86400000));
        activity.setEndDate(null);
        jdbc.update("DELETE FROM play_team_member"); jdbc.update("DELETE FROM play_team"); jdbc.update("DELETE FROM cms_registration"); jdbc.update("DELETE FROM audit_task");
    }

    @Test void threePersonTeamKeepsTheLastPlaceOpen() {
        PlayTeam team = team(3);
        paid(2L, 2, 10L); paid(3L, 2, 10L);
        PlayTeam second = service.join(team.getInviteCode(), 2L);
        assertEquals(2, second.getJoinedCount());
        assertEquals(PlayTeam.STATUS_RECRUITING, second.getStatus());
        assertEquals(3, service.join(team.getInviteCode(), 3L).getJoinedCount());
        assertEquals(PlayTeam.STATUS_LOCKED, mapper.selectPlayTeamById(team.getId()).getStatus());
    }

@Test void creatingATeamUsesTheAuthoritativeRegistrationSchema() {
        jdbc.update("INSERT INTO cms_registration (registration_no, owner_type, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount, payable_amount, payment_status, registration_status, pay_expire_time) VALUES ('paid', 2, 10, 1, 0, 1, 0, 0, 0, 2, 2, NOW())");
        PlayTeam created = service.create(1L, 2, 10L, null, 3);
        assertEquals(1, mapper.selectPlayTeamById(created.getId()).getJoinedCount());
        assertEquals(1, mapper.countActiveMembers(created.getId()));
    }

    @Test void capacitiesLockExactlyWhenFullAndQuitReopensOnePlace() {
        for (long member = 2; member <= 4; member++) paid(member, 2, 10L);
        for (int cap = 2; cap <= 4; cap++) {
            PlayTeam team = team(cap);
            for (long member = 2; member <= cap; member++) {
                PlayTeam joined = service.join(team.getInviteCode(), member);
                assertEquals((int) member, joined.getJoinedCount());
                assertEquals(member == cap ? 1 : 0, joined.getStatus());
            }
            assertThrows(IllegalStateException.class, () -> service.join(team.getInviteCode(), 99L));
            service.quit(team.getId(), (long) cap);
            assertEquals(0, mapper.selectPlayTeamById(team.getId()).getStatus());
            assertEquals(cap, service.join(team.getInviteCode(), (long) cap).getJoinedCount());
            assertEquals(cap, mapper.countActiveMembers(team.getId()));
        }
    }

    @Test void duplicateJoinRollsBackItsReservedSlot() {
        PlayTeam team = team(4);
        paid(2L, 2, 10L);
        service.join(team.getInviteCode(), 2L);
        assertThrows(IllegalStateException.class, () -> service.join(team.getInviteCode(), 2L));
        assertEquals(2, mapper.selectPlayTeamById(team.getId()).getJoinedCount());
        assertEquals(2, mapper.countActiveMembers(team.getId()));
        assertEquals(0, mapper.selectPlayTeamById(team.getId()).getStatus());
    }

    @Test void twoConcurrentPlayersCompeteForOneFinalPlace() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (long member = 2; member <= 6; member++) paid(member, 2, 10L);
            for (int cap = 2; cap <= 4; cap++) {
                PlayTeam team = team(cap);
                for (long member = 2; member < cap; member++) service.join(team.getInviteCode(), member);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = workers.submit(concurrentJoin(team.getInviteCode(), cap + 1L, ready, start));
                Future<Boolean> second = workers.submit(concurrentJoin(team.getInviteCode(), cap + 2L, ready, start));
                assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(cap, mapper.selectPlayTeamById(team.getId()).getJoinedCount());
                assertEquals(cap, mapper.countActiveMembers(team.getId()));
                assertEquals(1, mapper.selectPlayTeamById(team.getId()).getStatus());
            }
        } finally { workers.shutdownNow(); }
    }

    private Callable<Boolean> concurrentJoin(String inviteCode, long memberId, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                service.join(inviteCode, memberId);
                return true;
            } catch (IllegalStateException full) {
                assertEquals("队伍已满或已停止招募", full.getMessage());
                return false;
            }
        };
    }

@Test void otherMembersUnpaidCancelledRefundedAndDeletedOrdersCannotCreateTeams() {
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("INSERT INTO cms_registration (registration_no, owner_type, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount, payable_amount, payment_status, registration_status, pay_expire_time) VALUES ('order', 2, 10, 1, 0, 2, 0, 0, 0, 2, 2, NOW())");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("UPDATE cms_registration SET member_id=1, payment_status=0, registration_status=1");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("UPDATE cms_registration SET payment_status=2, registration_status=3");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("UPDATE cms_registration SET payment_status=4, registration_status=2");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("UPDATE cms_registration SET payment_status=2, owner_id=11");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        jdbc.update("DELETE FROM cms_registration");
        assertThrows(IllegalStateException.class, () -> service.create(1L, 2, 10L, null, 3));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_team", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM play_team_member", Integer.class));
    }

    // ---------- 地图组队 P 方案:公开申请 ----------

    @Test void newTeamsDefaultToPublicAndTheirLeaderCanSwitchBothWays() {
        paid(1L, 2, 10L);
        PlayTeam created = service.create(1L, 2, 10L, null, 3);
        assertEquals(2, mapper.selectPlayTeamById(created.getId()).getJoinMode(), "不传 joinMode 的新队伍默认公开(地图可见)");

        assertEquals(1, service.updateJoinMode(created.getId(), 1L, 1));
        assertEquals(1, mapper.selectPlayTeamById(created.getId()).getJoinMode(), "队长可改为仅邀请");
        assertEquals(2, service.updateJoinMode(created.getId(), 1L, 2));
        assertEquals(2, mapper.selectPlayTeamById(created.getId()).getJoinMode(), "队长也可再改回公开");

        assertEquals("只有队长可以操作", assertThrows(IllegalStateException.class,
                () -> service.updateJoinMode(created.getId(), 2L, 1)).getMessage());
        assertEquals("组队方式有误", assertThrows(IllegalArgumentException.class,
                () -> service.updateJoinMode(created.getId(), 1L, 3)).getMessage());
        assertEquals(2, mapper.selectPlayTeamById(created.getId()).getJoinMode(), "失败的切换不得改库");
    }

    @Test void applyRequiresPublicTeamAndAPaidTicketForThisSessionNotASelfPlayPass() {
        PlayTeam invite = team(4);
        // 邀请制且非队内成员 = 无权限可见 ⇒ 与「不存在」同口径(防探测),不再回 TEAM_NOT_PUBLIC。
        assertEquals("队伍不存在", assertThrows(IllegalArgumentException.class,
                () -> service.apply(invite.getId(), 2L, null)).getMessage());
        PlayTeam team = publicTeam(4);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.apply(team.getId(), 2L, null)));
        // 自玩通行证:owner_type=1 挂在主题 20 上,不经过场次 ⇒ 不能入队
        paid(2L, 1, 20L);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.apply(team.getId(), 2L, null)));
        paid(2L, 2, 11L);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.apply(team.getId(), 2L, null)));
        paid(2L, 2, 10L);
        Date before = new Date();
        Date expireAt = service.apply(team.getId(), 2L, null);
        long ttl = expireAt.getTime() - before.getTime();
        assertTrue(ttl > 86400000L - 60000 && ttl <= 86400000L, "有效期 24h:" + ttl);
        PlayTeamMember row = mapper.selectMemberRow(team.getId(), 2L);
        assertEquals(PlayTeamMember.STATUS_PENDING, row.getStatus());
        assertEquals(1, mapper.selectPlayTeamById(team.getId()).getJoinedCount(), "申请不占名额");
        assertEquals("APPLY_PENDING", rejection(() -> service.apply(team.getId(), 2L, null)));
        assertEquals("ALREADY_JOINED", rejection(() -> service.apply(team.getId(), 1L, null)));
    }

    @Test void applicationExpiresAtSessionStartWhenThatIsSoonerThan24Hours() {
        activity.setStartDate(new Date(System.currentTimeMillis() + 2 * 3600 * 1000L));
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        assertEquals(activity.getStartDate().getTime() / 1000, service.apply(team.getId(), 2L, null).getTime() / 1000);
        // C8-01 拍板:开场后仍可重新组队,申请窗口改判到场次结束,队长仍能同意。
        activity.setStartDate(new Date(System.currentTimeMillis() - 1000));
        activity.setEndDate(new Date(System.currentTimeMillis() + 3600 * 1000L));
        paid(3L, 2, 10L);
        assertEquals(activity.getEndDate().getTime() / 1000, service.apply(team.getId(), 3L, null).getTime() / 1000);
        service.handle(team.getId(), 1L, 3L, true);
        assertEquals(PlayTeamMember.STATUS_JOINED, mapper.selectMemberRow(team.getId(), 3L).getStatus());
    }

    @Test void rejectedApplicantCannotApplyToTheSameTeamAgainButWithdrawnCan() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L); paid(3L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        service.handle(team.getId(), 1L, 2L, false);
        assertEquals(PlayTeamMember.STATUS_REJECTED, mapper.selectMemberRow(team.getId(), 2L).getStatus());
        assertEquals("APPLY_REJECTED", rejection(() -> service.apply(team.getId(), 2L, null)));
        assertEquals(PlayTeamMember.STATUS_REJECTED, mapper.selectMemberRow(team.getId(), 2L).getStatus());

        service.apply(team.getId(), 3L, null);
        service.withdraw(team.getId(), 3L);
        assertEquals(PlayTeamMember.STATUS_WITHDRAWN, mapper.selectMemberRow(team.getId(), 3L).getStatus());
        assertEquals("APPLY_NOT_PENDING", rejection(() -> service.handle(team.getId(), 1L, 3L, true)));
        service.apply(team.getId(), 3L, null);
        service.handle(team.getId(), 1L, 3L, true);
        assertEquals(2, mapper.countActiveMembers(team.getId()));
        assertEquals(2, mapper.selectPlayTeamById(team.getId()).getJoinedCount());
    }

    @Test void expiredApplicationCannotBeApprovedAndTheJobMarksItExpired() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        jdbc.update("UPDATE play_team_member SET apply_expire_time = ? WHERE member_id = 2",
                new Date(System.currentTimeMillis() - 1000));
        assertTrue(service.applications(team.getId(), 1L).isEmpty(), "过期申请不出现在队长列表");
        assertEquals("APPLY_NOT_PENDING", rejection(() -> service.handle(team.getId(), 1L, 2L, true)));
        assertEquals(1, mapper.selectPlayTeamById(team.getId()).getJoinedCount(), "失败的同意必须回滚扣位");
        assertTrue(service.myApplications(2L).isEmpty());
        assertEquals(1, service.expireStaleApplications(new Date()));
        assertEquals(PlayTeamMember.STATUS_EXPIRED, mapper.selectMemberRow(team.getId(), 2L).getStatus());
        service.apply(team.getId(), 2L, null);
        assertEquals(PlayTeamMember.STATUS_PENDING, mapper.selectMemberRow(team.getId(), 2L).getStatus());
    }

    @Test void approvingTheLastPlaceExpiresEveryOtherPendingApplicationInTheSameTransaction() {
        PlayTeam team = publicTeam(2);
        paid(2L, 2, 10L); paid(3L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        service.apply(team.getId(), 3L, null);
        assertEquals(2, service.applications(team.getId(), 1L).size());
        java.util.List<java.util.Map<String, Object>> mine = service.myApplications(3L);
        assertEquals(1, mine.size());
        assertEquals("PENDING", mine.get(0).get("applyStatus"));
        assertFalse(mine.get(0).containsKey("inviteCode"));
        service.handle(team.getId(), 1L, 2L, true);
        assertEquals(PlayTeam.STATUS_LOCKED, mapper.selectPlayTeamById(team.getId()).getStatus());
        assertEquals(PlayTeamMember.STATUS_EXPIRED, mapper.selectMemberRow(team.getId(), 3L).getStatus());
        assertEquals("TEAM_FULL", rejection(() -> service.apply(team.getId(), 4L, null)));
    }

    @Test void teamUnderOpenReportAcceptsNoApplicationsUntilTheReviewCloses() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        jdbc.update("INSERT INTO audit_task (biz_type, biz_id, status, submitted_at) VALUES ('TEAM', ?, 0, NOW())", team.getId());
        assertEquals("TEAM_UNDER_REVIEW", rejection(() -> service.apply(team.getId(), 2L, null)));
        jdbc.update("UPDATE audit_task SET status = 3");
        assertEquals("TEAM_UNDER_REVIEW", rejection(() -> service.apply(team.getId(), 2L, null)));
        jdbc.update("UPDATE audit_task SET status = 1");
        service.apply(team.getId(), 2L, null);
        assertEquals(PlayTeamMember.STATUS_PENDING, mapper.selectMemberRow(team.getId(), 2L).getStatus());
    }

    @Test void upheldReportHidesTeamFromPublicWithoutTouchingMembers() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        assertEquals(1, mapper.hideFromPublic(team.getId()));
        assertEquals(1, mapper.expirePendingApplications(team.getId()));
        assertEquals(1, mapper.selectPlayTeamById(team.getId()).getJoinMode());
        assertEquals(1, mapper.countActiveMembers(team.getId()));
        // 改回邀请制后原申请人也不在队内 ⇒ 与不存在同口径,不得借申请接口探测该队伍。
        assertEquals("队伍不存在", assertThrows(IllegalArgumentException.class,
                () -> service.apply(team.getId(), 2L, null)).getMessage());
    }

    @Test void expiredApplicationCannotBeTurnedIntoAPermanentRejection() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        jdbc.update("UPDATE play_team_member SET apply_expire_time = ? WHERE member_id = 2",
                new Date(System.currentTimeMillis() - 1000));
        assertEquals("APPLY_NOT_PENDING", rejection(() -> service.handle(team.getId(), 1L, 2L, false)));
        service.expireStaleApplications(new Date());
        service.apply(team.getId(), 2L, null);
        assertEquals(PlayTeamMember.STATUS_PENDING, mapper.selectMemberRow(team.getId(), 2L).getStatus());
    }

    @Test void approvalRechecksTheTicketBecauseItMayHaveBeenRefundedMeanwhile() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        jdbc.update("UPDATE cms_registration SET payment_status = 4 WHERE member_id = 2");
        assertEquals("TICKET_REQUIRED", rejection(() -> service.handle(team.getId(), 1L, 2L, true)));
        assertEquals(1, mapper.selectPlayTeamById(team.getId()).getJoinedCount(), "扣位回滚");
        assertEquals(PlayTeamMember.STATUS_PENDING, mapper.selectMemberRow(team.getId(), 2L).getStatus());
    }

    @Test void rejectedOrKickedPlayersCannotComeBackThroughAnInviteCode() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        service.handle(team.getId(), 1L, 2L, false);
        assertThrows(IllegalStateException.class, () -> service.join(team.getInviteCode(), 2L));
        assertEquals(PlayTeamMember.STATUS_REJECTED, mapper.selectMemberRow(team.getId(), 2L).getStatus());
        assertEquals(1, mapper.selectPlayTeamById(team.getId()).getJoinedCount(), "被拒者走邀请码失败必须回滚扣位");
        paid(3L, 2, 10L);
        service.join(team.getInviteCode(), 3L);
        service.kick(team.getId(), 1L, 3L);
        assertThrows(IllegalStateException.class, () -> service.join(team.getInviteCode(), 3L));
        // 撤回后的待审行可以直接凭邀请码入队
        paid(4L, 2, 10L);
        service.apply(team.getId(), 4L, null);
        assertEquals(2, service.join(team.getInviteCode(), 4L).getJoinedCount());
    }

    @Test void inviteCodeJoinRequiresAPaidTicketForThisSessionLikeApply() {
        PlayTeam team = team(4);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.join(team.getInviteCode(), 2L)), "无票不能凭邀请码入队");
        paid(2L, 1, 20L);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.join(team.getInviteCode(), 2L)), "自玩通行证 owner_type=1 不能入队");
        paid(2L, 2, 11L);
        assertEquals("TICKET_REQUIRED", rejection(() -> service.join(team.getInviteCode(), 2L)), "别场次的票不能入队");
        paid(2L, 2, 10L);
        assertEquals(2, service.join(team.getInviteCode(), 2L).getJoinedCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM play_team_member WHERE team_id = ? AND member_id = 2 AND status = 1",
                Integer.class, team.getId()));
        assertEquals(2, mapper.selectPlayTeamById(team.getId()).getJoinedCount(), "失败尝试不得占名额");
    }

    @Test void applyToAnInviteOnlyTeamLooksExactlyLikeAMissingTeam() {
        PlayTeam inviteOnly = team(4);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.apply(999999L, 2L, null));
        assertEquals("队伍不存在", missing.getMessage());
        IllegalArgumentException invisible = assertThrows(IllegalArgumentException.class,
                () -> service.apply(inviteOnly.getId(), 2L, null));
        assertEquals(missing.getClass(), invisible.getClass());
        assertEquals(missing.getMessage(), invisible.getMessage(), "私密队伍不得比不存在的队伍多泄露一个错误口径");
        // 队内成员看得见自己的队伍:按邀请制语义明确拒绝,不被本次统一影响。
        paid(1L, 2, 10L);
        assertEquals("TEAM_NOT_PUBLIC", rejection(() -> service.apply(inviteOnly.getId(), 1L, null)));
    }

    @Test void disbandingExpiresPendingApplications() {
        PlayTeam team = publicTeam(4);
        paid(2L, 2, 10L);
        service.apply(team.getId(), 2L, null);
        service.disband(team.getId(), 1L);
        assertEquals(PlayTeamMember.STATUS_EXPIRED, mapper.selectMemberRow(team.getId(), 2L).getStatus());
    }

    @Test void twoConcurrentApprovalsForTheLastPlaceNeverOverfillTheTeam() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                reset();
                PlayTeam team = publicTeam(2);
                paid(2L, 2, 10L); paid(3L, 2, 10L);
                service.apply(team.getId(), 2L, null);
                service.apply(team.getId(), 3L, null);
                CountDownLatch start = new CountDownLatch(1);
                Callable<Boolean> approve2 = approval(team.getId(), 2L, start);
                Callable<Boolean> approve3 = approval(team.getId(), 3L, start);
                Future<Boolean> first = workers.submit(approve2);
                Future<Boolean> second = workers.submit(approve3);
                start.countDown();
                assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertEquals(2, mapper.selectPlayTeamById(team.getId()).getJoinedCount());
                assertEquals(2, mapper.countActiveMembers(team.getId()));
                assertEquals(PlayTeam.STATUS_LOCKED, mapper.selectPlayTeamById(team.getId()).getStatus());
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM play_team_member WHERE team_id = ? AND status = 6", Integer.class, team.getId()));
            }
        } finally { workers.shutdownNow(); }
    }

    private Callable<Boolean> approval(Long teamId, Long memberId, CountDownLatch start) {
        return () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                service.handle(teamId, 1L, memberId, true);
                return true;
            } catch (IPlayTeamService.TeamRejection lost) {
                assertTrue(lost.getErrorCode().equals("TEAM_FULL") || lost.getErrorCode().equals("APPLY_NOT_PENDING"),
                        lost.getErrorCode());
                return false;
            }
        };
    }

    private String rejection(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(IPlayTeamService.TeamRejection.class, call).getErrorCode();
    }

    private void paid(Long memberId, int ownerType, Long ownerId) {
        jdbc.update("INSERT INTO cms_registration (registration_no, owner_type, owner_id, ticket_id, ticket_price, member_id, original_amount, total_amount, payable_amount, payment_status, registration_status, pay_expire_time) VALUES (?, ?, ?, 1, 0, ?, 0, 0, 0, 2, 2, NOW())",
                java.util.UUID.randomUUID().toString().substring(0, 30), ownerType, ownerId, memberId);
    }

    private PlayTeam publicTeam(int cap) {
        PlayTeam team = team(cap);
        jdbc.update("UPDATE play_team SET join_mode = 2 WHERE id = ?", team.getId());
        team.setJoinMode(2);
        return team;
    }

    private PlayTeam team(int cap) {
        PlayTeam team = new PlayTeam();
        team.setOwnerType(2); team.setOwnerId(10L); team.setLeaderMemberId(1L);
        team.setInviteCode(java.util.UUID.randomUUID().toString().replace("-", ""));
        team.setJoinMode(1); team.setMaxMembers(cap); team.setJoinedCount(1); team.setStatus(0);
        team.setExpireTime(new Date(System.currentTimeMillis() + 86400000)); mapper.insertPlayTeam(team);
        PlayTeamMember leader = new PlayTeamMember(); leader.setTeamId(team.getId()); leader.setMemberId(1L); leader.setRole(1); leader.setStatus(1);
        mapper.insertPlayTeamMember(leader);
        return team;
    }
}
