package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.ClubGovernanceService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.impl.ClubGovernanceServiceImpl;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.business.service.impl.ClubParticipationGuardImpl;
import com.chengyinhub.business.service.support.CommittedBusinessNotice;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 俱乐部入会审批 + 成员治理(封禁/解封/举报)发布验收卡,在真 MySQL 8.0.45 上跑。
 *
 * <p><b>真的部分</b>:表结构逐表取自仓库真实 DDL —— club / club_member / ums_member / player_growth 照抄
 * {@code sql/base_schema_20260818.sql},再按日期顺序叠后续迁移:
 * {@code migration_club_open_settings_20260909}(club 三开关列)、{@code migration_club_join_message_20260910}、
 * {@code migration_20261012_club_join_review}(审核留痕三列)、{@code migration_club_name_unique_20261020};
 * business_audit_event 取 {@code migration_business_audit_event_20260823} + {@code migration_approval_governance_20260828}
 * 里对它的 ALTER(该文件的触发器已被 0830 修复迁移删掉,故不建);
 * club_governance_ban / _case 取 {@code migration_club_role_governance_20260823} + {@code migration_club_role_z_null_safety_repair_20260826}
 * 对 case 的 CHECK 重建。mapper 用真 XML(ClubMapper / ClubMemberMapper / ClubGovernanceMapper / BusinessAuditEventMapper),
 * 服务用真 {@link ClubMemberServiceImpl} / {@link ClubGovernanceServiceImpl} / {@link ClubParticipationGuardImpl},
 * 外面套与生产同语义的 TransactionInterceptor 代理。</p>
 *
 * <p><b>mock 的部分</b>(都不是存储):站内通知 {@link CommittedBusinessNotice}、俱乐部权限解析
 * {@link IClubAccessService}(主理人路径本就不走它)、IM 退群、角色回收、会员权益收口。</p>
 *
 * <p>卡片:
 * <ul>
 *   <li>C1 入会审批通过:club_member 0→1、join_time 刷新、member_count 恰好 +1;二次通过 CAS 0 行、计数不再动;
 *       另一俱乐部同一申请人那行不被动(负控)。</li>
 *   <li>C2 拒绝:0→2、计数不动、review_reason/reviewer_member_id/reviewed_at 落库;待审列表不再出现;
 *       非主理人审批被拒且一行不写(负控)。</li>
 *   <li>C3 封禁:只删本俱乐部那条成员关系、有效封禁只在本俱乐部可见;unbanCas 过期 version / 外俱乐部 → 0 行;
 *       同俱乐部重复 ban_request_id 撞唯一键,换俱乐部不撞(负控)。</li>
 *   <li>C6 举报:filer = 调用者;同 requestId 重放查到同一行、不产生第二行;待复核主体查询不跨俱乐部。</li>
 * </ul>
 */
class ClubJoinGovernanceMySqlIT {

    private static final String SUITE = "club_join_governance";

    static final long CLUB_A = 101L, CLUB_B = 102L;
    static final long OWNER_A = 9001L, OWNER_B = 9002L;
    /** 两个俱乐部都在待审的申请人。 */
    static final long APPLICANT = 7001L;
    /** 两个俱乐部都是正常成员的被治理对象。 */
    static final long MEMBER_M = 7002L;
    /** 只在 A 的正常成员,用来举报。 */
    static final long REPORTER = 7003L;
    /** 哪个俱乐部都不在的局外人。 */
    static final long OUTSIDER = 7999L;
    static final int COUNT_A = 5, COUNT_B = 8;

    static DriverManagerDataSource source;
    static String database;
    static JdbcTemplate db;
    static SqlSessionTemplate sql;
    static ClubMemberMapper memberMapper;
    static ClubGovernanceMapper governanceMapper;
    static IClubMemberService members;
    static ClubGovernanceService governance;
    static ClubParticipationGuardImpl guard;
    static CommittedBusinessNotice notice;
    static IClubAccessService access;

    @BeforeAll
    static void openDatabase() throws Exception {
        String url = MybatisItDatabase.url(SUITE);
        database = MybatisItDatabase.databaseName(SUITE);
        source = new DriverManagerDataSource(url, MybatisItDatabase.user(), MybatisItDatabase.password());
        db = new JdbcTemplate(source);
        String version = db.queryForObject("select version()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        assertEquals(database, db.queryForObject("select database()", String.class), "只许连本类专属 scratch 库");

        String base = read("../sql/base_schema_20260818.sql");
        for (String table : List.of("club", "club_member", "ums_member", "player_growth")) {
            db.execute("DROP TABLE IF EXISTS `" + table + "`");
            db.execute(statement(base, "CREATE TABLE `" + table + "` ("));
        }
        try (Connection connection = source.getConnection()) {
            // 基线之后对 club / club_member 的迁移,按日期顺序
            ItSchema.applyMigrations(connection, "sql/migration_club_open_settings_20260909.sql");
            ItSchema.applyMigrations(connection, "sql/migration_club_join_message_20260910.sql");
            ItSchema.applyMigrations(connection, "sql/migration_20261012_club_join_review.sql");
            ItSchema.applyMigrations(connection, "sql/migration_club_name_unique_20261020.sql");
            // 治理表依赖 business_audit_event 已存在(迁移头注释写明)
            ItSchema.applyMigrations(connection, "sql/migration_business_audit_event_20260823.sql",
                    "sql/migration_club_role_governance_20260823.sql");
        }
        db.execute(statement(read("sql/migration_approval_governance_20260828.sql"),
                "ALTER TABLE business_audit_event"));
        db.execute(statement(read("sql/migration_club_role_z_null_safety_repair_20260826.sql"),
                "ALTER TABLE club_governance_case"));

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/ClubMapper.xml"),
                new ClassPathResource("mapper/business/ClubMemberMapper.xml"),
                new ClassPathResource("mapper/business/ClubGovernanceMapper.xml"),
                new ClassPathResource("mapper/business/BusinessAuditEventMapper.xml"));
        sql = new SqlSessionTemplate(factory.getObject());
        memberMapper = sql.getMapper(ClubMemberMapper.class);
        governanceMapper = sql.getMapper(ClubGovernanceMapper.class);
        ClubMapper clubMapper = sql.getMapper(ClubMapper.class);

        guard = new ClubParticipationGuardImpl();
        ReflectionTestUtils.setField(guard, "clubGovernanceMapper", governanceMapper);
        notice = mock(CommittedBusinessNotice.class);
        access = mock(IClubAccessService.class);
        for (long[] pair : new long[][] {{OWNER_A, CLUB_A}, {OWNER_B, CLUB_B}}) {
            when(access.require(pair[0], pair[1], null, ClubPermission.MEMBER_MANAGE)).thenReturn(
                    new ClubAccessContext(pair[0], pair[1], pair[0], null, Set.of(ClubRoleCode.CLUB_OWNER)));
        }

        ClubMemberServiceImpl memberTarget = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(memberTarget, "clubMemberMapper", memberMapper);
        ReflectionTestUtils.setField(memberTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(memberTarget, "businessNotice", notice);
        ReflectionTestUtils.setField(memberTarget, "clubAccessService", access);
        ReflectionTestUtils.setField(memberTarget, "clubParticipationGuard", guard);
        ReflectionTestUtils.setField(memberTarget, "clubRoleAssignmentService", mock(IClubRoleAssignmentService.class));
        ReflectionTestUtils.setField(memberTarget, "imService", mock(IImService.class));
        members = proxy(memberTarget, IClubMemberService.class);

        ClubGovernanceServiceImpl governanceTarget = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(governanceTarget, "clubAccessService", access);
        ReflectionTestUtils.setField(governanceTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(governanceTarget, "clubMemberMapper", memberMapper);
        ReflectionTestUtils.setField(governanceTarget, "clubGovernanceMapper", governanceMapper);
        ReflectionTestUtils.setField(governanceTarget, "businessAuditEventMapper",
                sql.getMapper(BusinessAuditEventMapper.class));
        ReflectionTestUtils.setField(governanceTarget, "clubParticipationGuard", guard);
        ReflectionTestUtils.setField(governanceTarget, "cmsActivityMapper", mock(CmsActivityMapper.class));
        ReflectionTestUtils.setField(governanceTarget, "imService", mock(IImService.class));
        ReflectionTestUtils.setField(governanceTarget, "clubMembershipService", mock(IClubMembershipService.class));
        ReflectionTestUtils.setField(governanceTarget, "clubRoleAssignmentService", mock(IClubRoleAssignmentService.class));
        ReflectionTestUtils.setField(governanceTarget, "clubLeaderService", mock(IClubLeaderService.class));
        ReflectionTestUtils.setField(governanceTarget, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(governanceTarget, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(governanceTarget, "roleService", mock(IRoleService.class));
        ReflectionTestUtils.setField(governanceTarget, "merchantAccessService", mock(IMerchantAccessService.class));
        governance = proxy(governanceTarget, ClubGovernanceService.class);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (database == null) return;
        try (Connection admin = DriverManager.getConnection(
                "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            ResultSet left = st.executeQuery(
                    "select count(*) from information_schema.schemata where schema_name = '" + database + "'");
            left.next();
            assertEquals(0, left.getInt(1), "scratch 库必须删干净");
        }
    }

    /** 两个审核通过的俱乐部;APPLICANT 两边都待审、MEMBER_M 两边都是正式成员、REPORTER 只在 A。 */
    @BeforeEach
    void seed() {
        for (String table : List.of("business_audit_event", "club_governance_case", "club_governance_ban",
                "club_member", "club", "ums_member")) {
            db.update("delete from " + table);
        }
        db.update("insert into club(id, member_id, name, status, member_count, join_policy, del_flag)"
                + " values(?, ?, '城南夜跑团', 1, ?, 1, 0), (?, ?, '城北徒步社', 1, ?, 1, 0)",
                CLUB_A, OWNER_A, COUNT_A, CLUB_B, OWNER_B, COUNT_B);
        db.update("insert into ums_member(id, nickname) values(?, '申请人'), (?, '成员M'), (?, '举报人')",
                APPLICANT, MEMBER_M, REPORTER);
        db.update("insert into club_member(club_id, member_id, role, status, join_message, join_time, del_flag) values"
                        + " (?, ?, 0, 0, '想跑步', '2026-01-01 08:00:00', 0),"
                        + " (?, ?, 0, 0, '想徒步', '2026-01-01 08:00:00', 0),"
                        + " (?, ?, 0, 1, null, '2026-02-01 08:00:00', 0),"
                        + " (?, ?, 0, 1, null, '2026-02-01 08:00:00', 0),"
                        + " (?, ?, 0, 1, null, '2026-02-01 08:00:00', 0)",
                CLUB_A, APPLICANT, CLUB_B, APPLICANT, CLUB_A, MEMBER_M, CLUB_B, MEMBER_M, CLUB_A, REPORTER);
        clearInvocations(notice);
    }

    // ===================== C1 入会审批通过 =====================

    /**
     * C1:主理人通过入会申请。status 0→1、join_time 刷到审批时刻、member_count 恰好 +1,审核留痕落库;
     * 负控:同一申请人在 B 俱乐部的待审行、B 的计数一点不动;二次通过被状态机拒绝,
     * 且 recordJoinReview 的状态 CAS 对已处理行 0 行。
     */
    @Test
    void c1_approveJoinRequest_flipsPendingOnce_andBumpsOnlyThisClubsCount() {
        Date before = new Date(System.currentTimeMillis() - 2000);
        ClubMember pending = memberMapper.selectAnyByClubAndMember(CLUB_A, APPLICANT);
        assertEquals(0, pending.getStatus(), "前置:A 里是待审行");

        members.reviewJoinRequest(CLUB_A, APPLICANT, OWNER_A, true, "欢迎");

        Map<String, Object> row = memberRow(CLUB_A, APPLICANT);
        assertEquals(1, ((Number) row.get("status")).intValue(), "通过后 status 必须 0→1");
        assertEquals(0, ((Number) row.get("role")).intValue(), "通过后是普通成员,不是管理员");
        Date joinTime = timeOf("join_time", CLUB_A, APPLICANT);
        assertTrue(joinTime != null && !joinTime.before(before), "join_time 必须刷成审批时刻,实际 " + joinTime);
        assertEquals("欢迎", row.get("review_reason"), "审核理由落库");
        assertEquals(OWNER_A, ((Number) row.get("reviewer_member_id")).longValue(), "审核人 = 主理人");
        assertNotNull(row.get("reviewed_at"), "审核时间落库");
        assertEquals(COUNT_A + 1, memberCount(CLUB_A), "member_count 恰好 +1");
        assertEquals(pending.getId(), memberMapper.selectByClubAndMember(CLUB_A, APPLICANT).getId(),
                "正式成员查询能查到同一行(没有新插一行)");
        assertEquals(1, db.queryForObject("select count(*) from club_member where club_id=? and member_id=?",
                Integer.class, CLUB_A, APPLICANT), "A 里该申请人始终只有一行");
        verify(notice, times(1)).sendAfterCommit(eq(APPLICANT), eq("入会申请已通过"), anyString(),
                eq("club-join-review"), eq(pending.getId()));

        // 负控:另一俱乐部同一申请人那行、那家计数,一点不动
        Map<String, Object> other = memberRow(CLUB_B, APPLICANT);
        assertEquals(0, ((Number) other.get("status")).intValue(), "B 的待审行不能被 A 的审批带着变");
        assertNull(other.get("reviewer_member_id"), "B 的待审行不能被写审核人");
        assertEquals("", other.get("review_reason"), "B 的待审行审核理由保持迁移默认空串");
        assertEquals(COUNT_B, memberCount(CLUB_B), "B 的 member_count 不动");

        // 二次通过:状态机拒绝,计数不再 +1
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> members.reviewJoinRequest(CLUB_A, APPLICANT, OWNER_A, true, "再来一次"));
        assertEquals("入会申请不存在或已处理", again.getMessage(), "二次通过必须被拒");
        assertEquals(COUNT_A + 1, memberCount(CLUB_A), "二次通过后 member_count 仍只 +1");
        assertEquals("欢迎", memberRow(CLUB_A, APPLICANT).get("review_reason"), "二次通过不能覆盖首个审核理由");
        assertEquals(0, memberMapper.recordJoinReview(CLUB_A, APPLICANT, 0, "x", OWNER_A, new Date()),
                "审核 CAS 以待审态为前提:已通过行按 status=0 匹配必须 0 行");
        verify(notice, times(1)).sendAfterCommit(anyLong(), anyString(), anyString(), anyString(), any());
    }

    /**
     * C1 负控:被本俱乐部有效封禁的人,审批通过会被真 ClubParticipationGuardImpl 拦下,一行不写;
     * 封禁只在 A,B 里同一人照常能通过。
     */
    @Test
    void c1_approveIsBlockedByActiveBanInThisClubOnly() {
        governanceMapper.insertClubGovernanceBan(ban(CLUB_A, APPLICANT, "ban-applicant"));

        ServiceException blocked = assertThrows(ServiceException.class,
                () -> members.reviewJoinRequest(CLUB_A, APPLICANT, OWNER_A, true, null));
        assertEquals(HttpStatus.FORBIDDEN, blocked.getCode(), "被封禁者不得被审批入会");
        assertEquals(0, ((Number) memberRow(CLUB_A, APPLICANT).get("status")).intValue(), "被拦后仍是待审");
        assertEquals(COUNT_A, memberCount(CLUB_A), "被拦后 A 计数不动");

        members.reviewJoinRequest(CLUB_B, APPLICANT, OWNER_B, true, null);
        assertEquals(1, ((Number) memberRow(CLUB_B, APPLICANT).get("status")).intValue(), "A 的封禁不外溢到 B");
        assertEquals(COUNT_B + 1, memberCount(CLUB_B), "B 计数 +1");
    }

    // ===================== C2 拒绝 =====================

    /**
     * C2:主理人拒绝。status 0→2、member_count 不动、审核三列落库,待审列表不再返回;
     * 负控:非主理人(A 的普通成员)审批被拒且一行不写;B 的待审列表仍含该申请人。
     */
    @Test
    void c2_rejectJoinRequest_persistsReview_andLeavesCountUntouched() {
        assertEquals(List.of(APPLICANT), pendingMemberIds(CLUB_A), "前置:A 的待审列表含申请人");

        IllegalArgumentException notOwner = assertThrows(IllegalArgumentException.class,
                () -> members.reviewJoinRequest(CLUB_A, APPLICANT, REPORTER, false, "我来拒"));
        assertEquals("只有主理人或管理员可审批入会申请", notOwner.getMessage(), "普通成员不得审批");
        assertEquals(0, ((Number) memberRow(CLUB_A, APPLICANT).get("status")).intValue(), "越权审批一行不写");

        Date before = new Date(System.currentTimeMillis() - 2000);
        members.reviewJoinRequest(CLUB_A, APPLICANT, OWNER_A, false, "  资料不全  ");

        Map<String, Object> row = memberRow(CLUB_A, APPLICANT);
        assertEquals(2, ((Number) row.get("status")).intValue(), "拒绝后 status 必须 0→2");
        assertEquals("资料不全", row.get("review_reason"), "审核理由去首尾空白后落库");
        assertEquals(OWNER_A, ((Number) row.get("reviewer_member_id")).longValue(), "审核人 = 主理人");
        Date reviewedAt = timeOf("reviewed_at", CLUB_A, APPLICANT);
        assertTrue(reviewedAt != null && !reviewedAt.before(before), "reviewed_at 落库为审批时刻,实际 " + reviewedAt);
        assertEquals(1, db.queryForObject("select count(*) from club_member where club_id=? and member_id=?"
                + " and join_time = '2026-01-01 08:00:00'", Integer.class, CLUB_A, APPLICANT),
                "拒绝不改 join_time(申请时刻保留)");
        assertEquals(COUNT_A, memberCount(CLUB_A), "拒绝不动 member_count");
        assertTrue(pendingMemberIds(CLUB_A).isEmpty(), "selectPendingByClub 不再返回已拒绝的申请");
        assertEquals(0, memberMapper.countPendingByClub(CLUB_A), "A 待审计数归零");
        assertNull(memberMapper.selectByClubAndMember(CLUB_A, APPLICANT), "已拒绝不是成员");
        ClubMember readBack = memberMapper.selectAnyByClubAndMember(CLUB_A, APPLICANT);
        assertEquals("资料不全", readBack.getReviewReason(), "mapper 回读审核理由");
        assertEquals(OWNER_A, readBack.getReviewerMemberId(), "mapper 回读审核人");
        verify(notice, times(1)).sendAfterCommit(eq(APPLICANT), eq("入会申请未通过"), anyString(),
                eq("club-join-review"), eq(readBack.getId()));

        assertEquals(List.of(APPLICANT), pendingMemberIds(CLUB_B), "B 的待审列表不受 A 拒绝影响");
        assertEquals(COUNT_B, memberCount(CLUB_B), "B 计数不动");
    }

    // ===================== C3 封禁 / 解封 =====================

    /**
     * C3:主理人封禁 A 的成员 M。封禁行落库、deleteByClubAndMember 只删 A 那条关系、A 计数 -1;
     * selectEffectiveByClubAndMember 只在 A 命中;真守卫在 A 拦、在 B 放;重放同 requestId 409 且不多写。
     */
    @Test
    void c3_banRemovesOnlyThisClubsMembership_andIsScopedToThisClub() {
        Date expires = new Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000);
        ClubGovernanceBan row = governance.ban(OWNER_A, CLUB_A, MEMBER_M, "多次辱骂成员", expires, "ban-req-1");
        assertNotNull(row.getId(), "封禁行回填主键");

        Map<String, Object> stored = db.queryForMap("select * from club_governance_ban where id=?", row.getId());
        assertEquals(CLUB_A, ((Number) stored.get("club_id")).longValue(), "封禁行属于 A");
        assertEquals("ACTIVE", stored.get("status"), "新封禁为 ACTIVE");
        assertEquals("CLUB", stored.get("source_type"), "主理人封禁来源为 CLUB");
        assertEquals(OWNER_A, ((Number) stored.get("banned_by_member_id")).longValue(), "封禁人 = 主理人");
        assertEquals(0, ((Number) stored.get("version")).intValue(), "初始 version=0");
        assertEquals(CLUB_A + ":" + MEMBER_M, stored.get("active_ban_key"), "生成列 active_ban_key 落值");

        assertEquals(0, db.queryForObject("select count(*) from club_member where club_id=? and member_id=?",
                Integer.class, CLUB_A, MEMBER_M), "A 里 M 的成员关系被删除");
        assertEquals(1, db.queryForObject("select count(*) from club_member where club_id=? and member_id=? and status=1",
                Integer.class, CLUB_B, MEMBER_M), "B 里 M 的成员关系原样保留");
        assertNotNull(memberMapper.selectByClubAndMember(CLUB_A, REPORTER), "A 的其他成员不受影响");
        assertEquals(COUNT_A - 1, memberCount(CLUB_A), "A 计数恰好 -1");
        assertEquals(COUNT_B, memberCount(CLUB_B), "B 计数不动");

        ClubGovernanceBan effective = governanceMapper.selectEffectiveByClubAndMember(CLUB_A, MEMBER_M, new Date());
        assertNotNull(effective, "A 的有效封禁可查到");
        assertEquals(row.getId(), effective.getId(), "查到的就是刚写的那行");
        assertNull(governanceMapper.selectEffectiveByClubAndMember(CLUB_B, MEMBER_M, new Date()),
                "A 的封禁在 B 查不到(只限本俱乐部)");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class,
                () -> guard.requireAllowed(CLUB_A, MEMBER_M)).getCode(), "真守卫在 A 拦下");
        guard.requireAllowed(CLUB_B, MEMBER_M);

        assertEquals(1, db.queryForObject("select count(*) from business_audit_event where domain_id=?"
                + " and action='CLUB_MEMBER_BANNED' and request_id='ban-req-1'", Integer.class, CLUB_A),
                "封禁写一条审计");

        ServiceException replay = assertThrows(ServiceException.class,
                () -> governance.ban(OWNER_A, CLUB_A, REPORTER, "重放", expires, "ban-req-1"));
        assertEquals(HttpStatus.CONFLICT, replay.getCode(), "同 requestId 重放 409");
        assertEquals(1, db.queryForObject("select count(*) from club_governance_ban", Integer.class), "重放不多写封禁行");
        assertNotNull(memberMapper.selectByClubAndMember(CLUB_A, REPORTER), "重放不误删别人的成员关系");
    }

    /**
     * C3:deleteByClubAndMember 的 WHERE 带 club_id —— 只删指定俱乐部那条,同一会员在别的俱乐部的行不动。
     */
    @Test
    void c3_deleteByClubAndMember_removesOnlyThatClubsRow() {
        assertEquals(1, memberMapper.deleteByClubAndMember(CLUB_B, MEMBER_M), "删 B 的一行");
        assertEquals(0, memberMapper.deleteByClubAndMember(CLUB_B, MEMBER_M), "再删 0 行");
        assertNotNull(memberMapper.selectByClubAndMember(CLUB_A, MEMBER_M), "A 里 M 的关系还在");
        assertEquals(4, db.queryForObject("select count(*) from club_member", Integer.class), "总共只少一行");
    }

    /**
     * C3:unbanCas 乐观锁。过期 version → 0 行;对的 version 但外俱乐部 → 0 行;对的才 1 行、version+1;
     * 重复解封 → 0 行。服务层用过期 version 解封返回 409。
     */
    @Test
    void c3_unbanCas_staleVersionOrForeignClub_changesZeroRows() {
        ClubGovernanceBan row = ban(CLUB_A, MEMBER_M, "ban-cas");
        assertEquals(1, governanceMapper.insertClubGovernanceBan(row), "封禁行写入");

        assertEquals(HttpStatus.CONFLICT, assertThrows(ServiceException.class,
                () -> governance.unban(OWNER_A, CLUB_A, row.getId(), 1, "提前解封", "unban-stale")).getCode(),
                "服务层过期 version 解封 409");
        assertEquals(0, governanceMapper.unbanCas(row.getId(), CLUB_A, 1, OWNER_A, "x", "unban-1", "t"),
                "过期 version 必须 0 行");
        assertEquals(0, governanceMapper.unbanCas(row.getId(), CLUB_B, 0, OWNER_B, "x", "unban-1", "t"),
                "外俱乐部解封必须 0 行");
        Map<String, Object> still = db.queryForMap("select status, version from club_governance_ban where id=?", row.getId());
        assertEquals("ACTIVE", still.get("status"), "两次失败后仍 ACTIVE");
        assertEquals(0, ((Number) still.get("version")).intValue(), "两次失败后 version 不动");

        assertEquals(1, governanceMapper.unbanCas(row.getId(), CLUB_A, 0, OWNER_A, "已道歉", "unban-1", "t"),
                "正确 version + 本俱乐部 → 1 行");
        Map<String, Object> done = db.queryForMap("select * from club_governance_ban where id=?", row.getId());
        assertEquals("UNBANNED", done.get("status"), "解封后 UNBANNED");
        assertEquals(1, ((Number) done.get("version")).intValue(), "解封后 version+1");
        assertEquals("unban-1", done.get("unban_request_id"), "解封 requestId 落库");
        assertNull(done.get("active_ban_key"), "解封后生成列 active_ban_key 置空");
        assertEquals(0, governanceMapper.unbanCas(row.getId(), CLUB_A, 0, OWNER_A, "x", "unban-2", "t"),
                "重复解封 0 行");
        assertNull(governanceMapper.selectEffectiveByClubAndMember(CLUB_A, MEMBER_M, new Date()), "解封后不再有效");
    }

    /**
     * C3:uk_club_governance_ban_request(club_id, ban_request_id)。同俱乐部重复 request_id 撞唯一键;
     * 负控:同 request_id 换个俱乐部可以写(键按俱乐部隔离)。
     */
    @Test
    void c3_duplicateBanRequestId_violatesUniqueKey() {
        assertEquals(1, governanceMapper.insertClubGovernanceBan(ban(CLUB_A, MEMBER_M, "dup-1")), "首条写入");
        assertThrows(DuplicateKeyException.class,
                () -> governanceMapper.insertClubGovernanceBan(ban(CLUB_A, REPORTER, "dup-1")),
                "同俱乐部同 ban_request_id 必须撞唯一键");
        assertEquals(1, governanceMapper.insertClubGovernanceBan(ban(CLUB_B, MEMBER_M, "dup-1")),
                "同 request_id 在别的俱乐部可以写");
        assertEquals(2, db.queryForObject("select count(*) from club_governance_ban where ban_request_id='dup-1'",
                Integer.class), "最终只有 A、B 各一行");
    }

    // ===================== C6 举报 =====================

    /**
     * C6:成员举报同俱乐部成员。case 行 filer = 调用者、subject = 被举报人、PENDING;
     * selectCaseBySubmitRequestForUpdate 用 (club, filer, requestId) 查到同一行,换 filer 查不到;
     * 同 requestId 重放 409 且不产生第二行;局外人举报 403 不写行。
     */
    @Test
    void c6_clubReport_recordsCallerAsReporter_andReplayFindsSameRow() {
        ClubGovernanceCase filed = governance.submitReport(REPORTER, CLUB_A, "member", MEMBER_M,
                Map.of("occurredAt", "2026-10-20 20:00"), "群里多次辱骂", "rpt-1");
        assertNotNull(filed.getId(), "工单回填主键");

        Map<String, Object> row = db.queryForMap("select * from club_governance_case where id=?", filed.getId());
        assertEquals(REPORTER, ((Number) row.get("filer_member_id")).longValue(), "举报人 = 调用者");
        assertEquals(MEMBER_M, ((Number) row.get("subject_member_id")).longValue(), "被举报主体 = 目标成员");
        assertEquals("REPORT", row.get("case_type"), "类型 REPORT");
        assertEquals("MEMBER", row.get("target_type"), "目标类型规范为大写 MEMBER");
        assertEquals("PENDING", row.get("status"), "新工单 PENDING");
        assertEquals(CLUB_A, ((Number) row.get("club_id")).longValue(), "工单属于 A");

        ClubGovernanceCase replayLookup = governanceMapper.selectCaseBySubmitRequestForUpdate(CLUB_A, REPORTER, "rpt-1");
        assertNotNull(replayLookup, "重放查询能查到");
        assertEquals(filed.getId(), replayLookup.getId(), "重放查询查到的是同一行");
        assertNull(governanceMapper.selectCaseBySubmitRequestForUpdate(CLUB_A, OWNER_A, "rpt-1"),
                "换个 filer 同 requestId 查不到(请求键按举报人隔离)");
        assertNull(governanceMapper.selectCaseBySubmitRequestForUpdate(CLUB_B, REPORTER, "rpt-1"),
                "换个俱乐部同 requestId 查不到");

        ServiceException replay = assertThrows(ServiceException.class,
                () -> governance.submitReport(REPORTER, CLUB_A, "MEMBER", MEMBER_M, null, "再交一次", "rpt-1"));
        assertEquals(HttpStatus.CONFLICT, replay.getCode(), "同 requestId 重放 409");
        assertEquals(1, db.queryForObject("select count(*) from club_governance_case", Integer.class),
                "重放不产生第二行");
        assertEquals(1, db.queryForObject("select count(*) from business_audit_event"
                + " where action='CLUB_GOVERNANCE_REPORT_SUBMITTED'", Integer.class), "审计只有首次那一条");

        ServiceException outsider = assertThrows(ServiceException.class,
                () -> governance.submitReport(OUTSIDER, CLUB_A, "MEMBER", MEMBER_M, null, "路人举报", "rpt-x"));
        assertEquals(HttpStatus.FORBIDDEN, outsider.getCode(), "非本俱乐部成员不能举报");
        assertEquals(1, db.queryForObject("select count(*) from club_governance_case", Integer.class),
                "局外人举报一行不写");
    }

    /**
     * C6:selectPendingSubjectCaseForUpdate 按 (club, subject) 查待复核工单,不跨俱乐部。
     * M 在 A 被举报 → B 查 M 为空;B 主理人再在 B 举报 M → 两边各查到自己那张。
     */
    @Test
    void c6_pendingSubjectCase_doesNotMatchAcrossClubs() {
        ClubGovernanceCase inA = governance.submitReport(REPORTER, CLUB_A, "MEMBER", MEMBER_M, null, "A 里的举报", "rpt-a");
        ClubGovernanceCase pendingA = governanceMapper.selectPendingSubjectCaseForUpdate(CLUB_A, MEMBER_M);
        assertNotNull(pendingA, "A 里查到待复核工单");
        assertEquals(inA.getId(), pendingA.getId(), "就是刚交的那张");
        assertNull(governanceMapper.selectPendingSubjectCaseForUpdate(CLUB_B, MEMBER_M), "A 的工单在 B 查不到");
        assertNull(governanceMapper.selectPendingSubjectCaseForUpdate(CLUB_A, REPORTER), "举报人本身不是被举报主体");

        ClubGovernanceCase inB = governance.submitReport(OWNER_B, CLUB_B, "MEMBER", MEMBER_M, null, "B 里的举报", "rpt-a");
        assertEquals(inB.getId(), governanceMapper.selectPendingSubjectCaseForUpdate(CLUB_B, MEMBER_M).getId(),
                "B 查到的是 B 的工单");
        assertEquals(inA.getId(), governanceMapper.selectPendingSubjectCaseForUpdate(CLUB_A, MEMBER_M).getId(),
                "A 查到的仍是 A 的工单");
        assertEquals(List.of(inA.getId(), inB.getId()), db.queryForList(
                "select id from club_governance_case order by id", Long.class), "两张工单各自独立");
    }

    // ===================== helpers =====================

    private static ClubGovernanceBan ban(long clubId, long target, String requestId) {
        Date now = new Date();
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setClubId(clubId);
        row.setTargetMemberId(target);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(ClubGovernanceBan.SOURCE_CLUB);
        row.setBanReason("IT 封禁");
        row.setExpiresAt(new Date(now.getTime() + 3L * 24 * 3600 * 1000));
        row.setBannedByMemberId(clubId == CLUB_A ? OWNER_A : OWNER_B);
        row.setBannedAt(now);
        row.setBanRequestId(requestId);
        row.setVersion(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        return row;
    }

    private static Map<String, Object> memberRow(long clubId, long memberId) {
        // status/role 是 tinyint(1),驱动默认按 Boolean 读(2 也会读成 true),这里 +0 取真实数值。
        return db.queryForMap("select id, status + 0 as status, role + 0 as role, join_time, review_reason,"
                + " reviewer_member_id, reviewed_at from club_member where club_id=? and member_id=?", clubId, memberId);
    }

    /** 走 getTimestamp:驱动按 serverTimezone 换算成真实时刻,与服务写入的 java.util.Date 可直接比较。 */
    private static Date timeOf(String column, long clubId, long memberId) {
        return db.queryForObject("select " + column + " from club_member where club_id=? and member_id=?",
                java.sql.Timestamp.class, clubId, memberId);
    }

    private static int memberCount(long clubId) {
        return db.queryForObject("select member_count from club where id=?", Integer.class, clubId);
    }

    private static List<Long> pendingMemberIds(long clubId) {
        return memberMapper.selectPendingByClub(clubId).stream().map(ClubMember::getMemberId).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** 同 GameSessionLedgerMySqlFixture#statement:CREATE TABLE 从表尾 ") ENGINE=" 后找行末分号(列注释里可能有分号)。 */
    private static String statement(String script, String head) {
        int start = script.indexOf(head);
        assertTrue(start >= 0, "真 DDL 里找不到:" + head);
        int tail = head.startsWith("CREATE TABLE") ? script.indexOf("\n) ENGINE=", start) : start;
        return script.substring(start, script.indexOf(";\n", tail));
    }
}
