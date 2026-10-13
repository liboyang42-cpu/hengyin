package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.access.club.*;
import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual local MySQL8 + production MyBatis and transaction proxies; no production credentials. */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantIdentityMybatisIntegrationTest {
    private String database;
    private JdbcTemplate admin, db;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private IMmsMerchantService merchants;
    private IClubService clubs;
    private ClubGovernanceService governance;
    private IMerchantClaimService claims;
    private MerchantClaimMapper claimFacts;
    private MmsMerchantServiceImpl merchantTarget;
    private UmsMemberMapper members;
    private ICapabilityService capabilities;
    private CapabilityServiceImpl capabilityTarget;
    private MemberCapabilityMapper capabilityMapper;
    private MerchantOperatorMapper operators;

    @BeforeAll
    void database() throws Exception {
        // 2026-09-16: 原来硬编码 127.0.0.1:3317 —— 门禁注入的 CHENGYIN_TEST_MYSQL_* 被无视,
        // 接不上 CI 的库;统一走 MysqlIntegrationTestConfig(唯一临时 schema,参数与其它真库 IT 相同)。
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_merchant_identity");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.from(env, "it");
        database = mysql.databaseName();
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source =
                new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        db.execute(MERCHANT_DDL);
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY,role VARCHAR(30),user_type BIGINT,status BIGINT,del_flag INT,update_time DATETIME,point BIGINT DEFAULT 0)");
        db.execute("CREATE TABLE club(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,name VARCHAR(80),status BIGINT,del_flag INT DEFAULT 0,create_time DATETIME,KEY owner_idx(member_id),logo TEXT,cover TEXT,description TEXT,club_type TEXT,club_types TEXT,activity_prefs TEXT,experience TEXT,join_reason TEXT,case_images TEXT,city TEXT,keywords TEXT,style TEXT,max_event_size TEXT,avg_event_size TEXT,can_design_route TEXT,can_design_task TEXT,can_npc TEXT,can_merchant_coop TEXT,has_guide_cert TEXT,cert_images TEXT,category_id TEXT,address TEXT,leader_name TEXT,phone TEXT,level TEXT,tags TEXT,reject_reason TEXT,audit_by TEXT,audit_time DATETIME,member_count TEXT,priority_signup_enabled TEXT,member_discount_price TEXT,member_reserved_quota TEXT,public_visible TEXT,member_post_allowed TEXT,merchant_undertake_open TEXT,create_by TEXT,update_by TEXT,update_time DATETIME,remark TEXT)");
        db.execute("CREATE TABLE member_capability(id BIGINT AUTO_INCREMENT PRIMARY KEY,member_id BIGINT,capability VARCHAR(40),status INT,granted_at DATETIME,granted_by BIGINT,create_time DATETIME,update_time DATETIME,UNIQUE KEY uk_member_capability(member_id,capability))");
        db.execute("CREATE TABLE merchant_operator(id BIGINT PRIMARY KEY,merchant_id BIGINT,operator_member_id BIGINT,active_operator_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN operator_member_id ELSE NULL END) STORED,role_code VARCHAR(40),status VARCHAR(20),accepted_at DATETIME,revoked_by_member_id BIGINT,revoked_at DATETIME,revoke_reason VARCHAR(80),version INT DEFAULT 0,create_by VARCHAR(80),create_time DATETIME,update_by VARCHAR(80),update_time DATETIME,UNIQUE KEY active_member(active_operator_member_id))");
        db.execute("CREATE TABLE merchant_operator_invite(id BIGINT PRIMARY KEY,merchant_id BIGINT,status VARCHAR(20),revoked_by_member_id BIGINT,revoked_at DATETIME,revoke_reason VARCHAR(80),token_hash VARCHAR(64),version INT DEFAULT 0,update_time DATETIME)");
        db.execute("CREATE TABLE identity_points_receipt(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id BIGINT,change_points BIGINT,change_reason VARCHAR(80))");
        db.execute("CREATE TABLE club_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,club_id BIGINT,member_id BIGINT,role INT,status INT,join_time DATETIME,del_flag INT,UNIQUE KEY member_club(club_id,member_id))");
        db.execute("CREATE TABLE club_governance_owner_transfer(id BIGINT AUTO_INCREMENT PRIMARY KEY,club_id BIGINT,from_owner_member_id BIGINT,to_owner_member_id BIGINT,reason VARCHAR(500),request_id VARCHAR(80),transferred_at DATETIME,create_time DATETIME,UNIQUE KEY request_club(club_id,request_id))");
        db.execute("CREATE TABLE club_leader(id BIGINT PRIMARY KEY,member_id BIGINT,level INT,status INT,del_flag INT)");
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MmsMerchantMapper.xml"),
                new ClassPathResource("mapper/business/MerchantOperatorMapper.xml"),
                new ClassPathResource("mapper/business/MerchantOperatorInviteMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                new ClassPathResource("mapper/business/ClubMapper.xml"),
                new ClassPathResource("mapper/business/ClubMemberMapper.xml"),
                new ClassPathResource("mapper/business/ClubGovernanceMapper.xml"),
                new ClassPathResource("mapper/business/MemberCapabilityMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        members = sql.getMapper(UmsMemberMapper.class);
        MmsMerchantMapper merchantMapper = sql.getMapper(MmsMerchantMapper.class);
        ClubMapper clubMapper = sql.getMapper(ClubMapper.class);
        CapabilityServiceImpl caps = new CapabilityServiceImpl();
        capabilityTarget = caps;
        capabilityMapper = sql.getMapper(MemberCapabilityMapper.class);
        ReflectionTestUtils.setField(caps, "memberCapabilityMapper", sql.getMapper(MemberCapabilityMapper.class));
        manager = new DataSourceTransactionManager(source);
        capabilities = (ICapabilityService) proxy(caps);
        operators = mock(MerchantOperatorMapper.class,org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(MerchantOperatorMapper.class)));
        merchantTarget = new MmsMerchantServiceImpl();
        ReflectionTestUtils.setField(merchantTarget, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(merchantTarget, "umsMemberMapper", members);
        ReflectionTestUtils.setField(merchantTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(merchantTarget, "capabilityService", capabilities);
        ReflectionTestUtils.setField(merchantTarget, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(merchantTarget, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(merchantTarget, "merchantOperatorInviteMapper", sql.getMapper(MerchantOperatorInviteMapper.class));
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        merchants = (IMmsMerchantService) proxy(merchantTarget);
        ClubServiceImpl clubTarget = new ClubServiceImpl();
        IUmsMemberService accounts = mock(IUmsMemberService.class);
        when(accounts.lockMemberById(anyLong())).thenAnswer(c -> members.lockMemberById(c.getArgument(0)));
        when(accounts.selectUmsMemberById(anyLong())).thenAnswer(c -> db.queryForObject(
                "SELECT id,role,user_type,status,del_flag FROM ums_member WHERE id=?", (rs, n) -> {
                    UmsMember a = new UmsMember(); a.setId(rs.getLong(1)); a.setRole(rs.getString(2));
                    a.setUserType(rs.getLong(3)); a.setStatus(rs.getLong(4)); a.setDelFlag(rs.getInt(5)); return a;
                }, (Long) c.getArgument(0)));
        ClubLeaderMapper profiles = mock(ClubLeaderMapper.class);
        when(profiles.selectClubLeaderByMemberId(anyLong())).thenAnswer(c -> db.queryForObject(
                "SELECT id,member_id,level,status,del_flag FROM club_leader WHERE member_id=?", (rs,n) -> {
                    ClubLeader l = new ClubLeader(); l.setId(rs.getLong(1)); l.setMemberId(rs.getLong(2));
                    l.setLevel(rs.getInt(3)); l.setStatus(rs.getInt(4)); l.setDelFlag(rs.getInt(5)); return l;
                }, (Long) c.getArgument(0)));
        ReflectionTestUtils.setField(clubTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubTarget, "umsMemberService", accounts);
        ReflectionTestUtils.setField(clubTarget, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(clubTarget, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(clubTarget, "clubLeaderMapper", profiles);
        clubs = (IClubService) proxy(clubTarget);
        when(accounts.updateUmsMember(any(UmsMember.class))).thenAnswer(c -> members.updateUmsMember(c.getArgument(0)));
        IClubLeaderService leaderService = mock(IClubLeaderService.class);
        when(leaderService.selectClubLeaderByMemberId(anyLong())).thenAnswer(c -> profiles.selectClubLeaderByMemberId(c.getArgument(0)));
        IRoleService roles = mock(IRoleService.class);
        when(roles.resolveRole(any(UmsMember.class))).thenAnswer(c -> c.getArgument(0, UmsMember.class).getRole());
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(900L, 42L, null, ClubPermission.MEMBER_MANAGE)).thenReturn(
                new ClubAccessContext(900L,42L,900L,null,java.util.Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        ClubGovernanceMapper governanceMapper = mock(ClubGovernanceMapper.class,
                org.mockito.AdditionalAnswers.delegatesTo(sql.getMapper(ClubGovernanceMapper.class)));
        // Governance risk is outside this fixture; ownership/CAS/member mutations use actual XML.
        doReturn(null).when(governanceMapper).selectPendingSubjectCaseForUpdate(anyLong(),anyLong());
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
        ClubGovernanceServiceImpl transferTarget = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(transferTarget,"clubMapper",clubMapper);
        ReflectionTestUtils.setField(transferTarget,"clubMemberMapper",sql.getMapper(ClubMemberMapper.class));
        ReflectionTestUtils.setField(transferTarget,"clubGovernanceMapper",governanceMapper);
        ReflectionTestUtils.setField(transferTarget,"clubAccessService",access);
        ReflectionTestUtils.setField(transferTarget,"clubRoleAssignmentService",mock(IClubRoleAssignmentService.class));
        ReflectionTestUtils.setField(transferTarget,"clubLeaderService",leaderService);
        ReflectionTestUtils.setField(transferTarget,"umsMemberService",accounts);
        ReflectionTestUtils.setField(transferTarget,"roleService",roles);
        ReflectionTestUtils.setField(transferTarget,"capabilityService",capabilities);
        ReflectionTestUtils.setField(transferTarget,"merchantAccessService",mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(transferTarget,"businessAuditEventMapper",audits);
        governance = (ClubGovernanceService) proxy(transferTarget);
        MerchantClaimMapper claimMapper = mock(MerchantClaimMapper.class);
        claimFacts = claimMapper;
        MerchantClaim claim = new MerchantClaim();claim.setId(3L);claim.setMerchantId(77L);claim.setClaimantMemberId(901L);claim.setStatus("PENDING");claim.setVersion(0);
        when(claimMapper.selectById(3L)).thenReturn(claim);
        when(claimMapper.selectByIdForUpdate(3L)).thenReturn(claim);
        // The merchant owner CAS remains real; audit status 0 simulates a lost receipt.
        MerchantClaimServiceImpl claimTarget = new MerchantClaimServiceImpl(claimMapper,merchantMapper,members,operators,capabilities);
        ReflectionTestUtils.setField(claimTarget,"merchantService",merchants);
        claims = (IMerchantClaimService) proxy(claimTarget);
    }

    @AfterAll
    void cleanup() {
        if (database == null || admin == null) return;
        admin.execute("DROP DATABASE " + database);
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        System.out.println("scratch cleanup verified " + database);
    }

    @BeforeEach
    void seed() {
        for (String table : new String[]{"merchant_operator_invite","merchant_operator","identity_points_receipt","club_governance_owner_transfer","club_member","member_capability","club","mms_merchant","club_leader","ums_member"}) db.update("DELETE FROM " + table);
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag,update_time) VALUES(901,'club',3,1,0,NULL)");
        db.update("INSERT INTO club_leader VALUES(91,901,3,1,0)");
        capabilities.grant(901L, MemberCapability.CAP_CLUB_LEADER, 1L);
        reset(operators);
        when(claimFacts.auditCas(anyLong(),anyString(),anyInt(),anyString(),anyLong(),any(),anyString(),anyString())).thenReturn(0);
        ReflectionTestUtils.setField(merchantTarget, "umsMemberMapper", members);
        ReflectionTestUtils.setField(merchantTarget,"umsMemberService",mock(IUmsMemberService.class));
    }

    @Test
    void roleInfoKeepsOneIdentitySnapshotWhileMerchantActivationCommits() throws Exception {
        MmsMerchant pending = activeMerchant();
        pending.setStatus(0L); pending.setAccountStatus(0L);
        merchants.insertMmsMerchant(pending);
        CountDownLatch oldCapabilityRead = new CountDownLatch(1);
        CountDownLatch activationCommitted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean pauseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        ICapabilityService observedCapabilities = mock(ICapabilityService.class,
                org.mockito.AdditionalAnswers.delegatesTo(capabilities));
        doAnswer(call -> {
            boolean result = capabilities.hasCapability(call.getArgument(0), call.getArgument(1));
            if (pauseOnce.compareAndSet(true, false)) {
                oldCapabilityRead.countDown();
                assertTrue(activationCommitted.await(10, TimeUnit.SECONDS), "activation must commit between API reads");
            }
            return result;
        }).when(observedCapabilities).hasCapability(901L, MemberCapability.CAP_CLUB_LEADER);
        IUmsMemberService accounts = mock(IUmsMemberService.class);
        // The fixture has only the member columns needed by this API; JDBC still participates in its real transaction.
        when(accounts.selectUmsMemberById(901L)).thenAnswer(call -> db.queryForObject(
                "SELECT id,role,user_type FROM ums_member WHERE id=901", (rs, n) -> {
                    UmsMember member = new UmsMember(); member.setId(rs.getLong(1));
                    member.setRole(rs.getString(2)); member.setUserType(rs.getLong(3)); return member;
                }));
        IUserPermissionService permissions = mock(IUserPermissionService.class);
        when(permissions.getByRole(anyString())).thenAnswer(call -> new UserPermission());
        RoleServiceImpl roles = new RoleServiceImpl();
        ReflectionTestUtils.setField(roles, "capabilityService", capabilities);
        ReflectionTestUtils.setField(roles, "userPermissionService", permissions);
        com.chengyinhub.web.controller.api.ApiRoleController target = spy(new com.chengyinhub.web.controller.api.ApiRoleController());
        doReturn(901L).when(target).getAppUserId();
        ReflectionTestUtils.setField(target, "umsMemberService", accounts);
        ReflectionTestUtils.setField(target, "roleService", roles);
        ReflectionTestUtils.setField(target, "capabilityService", observedCapabilities);
        ReflectionTestUtils.setField(target, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(target, "clubService", clubs);
        ReflectionTestUtils.setField(target, "clubLeaderService", mock(IClubLeaderService.class));
        ReflectionTestUtils.setField(target, "clubMemberService", mock(IClubMemberService.class));
        ReflectionTestUtils.setField(target, "cmsTopicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(target, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(target, "smsCouponService", mock(ISmsCouponService.class));
        ProxyFactory apiProxy = new ProxyFactory(target);
        apiProxy.setProxyTargetClass(true);
        apiProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        com.chengyinhub.web.controller.api.ApiRoleController api =
                (com.chengyinhub.web.controller.api.ApiRoleController) apiProxy.getProxy();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<com.chengyinhub.common.core.domain.AjaxResult> response = executor.submit(api::roleInfo);
            assertTrue(oldCapabilityRead.await(10, TimeUnit.SECONDS));
            MmsMerchant approve = new MmsMerchant(); approve.setId(pending.getId());
            approve.setStatus(1L); approve.setAccountStatus(1L);
            assertEquals(1, merchants.updateMmsMerchant(approve));
            assertEquals("merchant", db.queryForObject("SELECT role FROM ums_member WHERE id=901", String.class));
            activationCommitted.countDown();
            java.util.Map<?, ?> oldSnapshot = (java.util.Map<?, ?>) response.get(10, TimeUnit.SECONDS).get("data");
            assertEquals("club", oldSnapshot.get("role"));
            assertEquals(true, oldSnapshot.get("isClubLeader"));
            assertEquals(false, oldSnapshot.get("isMerchant"), "one response cannot mix old leader with newly activated merchant");
            java.util.Map<?, ?> newSnapshot = (java.util.Map<?, ?>) api.roleInfo().get("data");
            assertEquals("merchant", newSnapshot.get("role"));
            assertEquals(false, newSnapshot.get("isClubLeader"));
            assertEquals(true, newSnapshot.get("isMerchant"));
        } finally {
            activationCommitted.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void pendingInactiveAndRejectedOwnedClubsAllBlockActivationWithoutSideEffects() {
        for (int status : new int[]{0,1,2,3}) {
            db.update("DELETE FROM club");
            db.update("INSERT INTO club(id,member_id,name,status,del_flag) VALUES(42,901,'must hand over',?,0)", status);
            ServiceException denied = assertThrows(ServiceException.class, () -> merchants.insertMmsMerchant(activeMerchant()));
            assertEquals(409, denied.getCode()); assertTrue(denied.getMessage().contains("42"));
            assertEquals(0, count("mms_merchant")); assertLeaderUnchanged();
        }
    }

    @Test
    void existingLeaderWithOwnedClubMayCreatePendingMerchantApplication() {
        db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(42,901,1,0)");
        MmsMerchant pending = new MmsMerchant();pending.setMemberId(901L);pending.setName("待审商家");pending.setStatus(0L);pending.setAccountStatus(0L);
        assertEquals(1,merchants.insertMmsMerchant(pending));
        assertEquals(0,db.queryForObject("SELECT status FROM mms_merchant",Integer.class));
        assertEquals(901L,db.queryForObject("SELECT member_id FROM club WHERE id=42",Long.class));
        assertLeaderUnchanged();
    }

    @Test
    void zeroClubLeaderSwitchesAtomicallyAndHistoricalProfileSurvives() {
        assertEquals(1, merchants.insertMmsMerchant(activeMerchant()));
        assertFalse(capabilities.hasCapability(901L, MemberCapability.CAP_CLUB_LEADER));
        assertTrue(capabilities.hasCapability(901L, MemberCapability.CAP_MERCHANT));
        assertEquals("merchant", db.queryForObject("SELECT role FROM ums_member WHERE id=901", String.class));
        assertEquals(2, db.queryForObject("SELECT user_type FROM ums_member WHERE id=901", Integer.class));
        assertEquals(3, db.queryForObject("SELECT level FROM club_leader WHERE id=91", Integer.class));
    }

    @Test
    void memberIdentityWriteFailureRollsBackInsertedMerchantAndBothCapabilities() {
        UmsMemberMapper failing = mock(UmsMemberMapper.class, org.mockito.AdditionalAnswers.delegatesTo(members));
        doReturn(0).when(failing).updateUmsMember(any(UmsMember.class));
        ReflectionTestUtils.setField(merchantTarget, "umsMemberMapper", failing);
        assertThrows(ServiceException.class, () -> merchants.insertMmsMerchant(activeMerchant()));
        assertEquals(0, count("mms_merchant")); assertLeaderUnchanged();
    }

    @Test
    void failedCapabilityGrantOrRevokeRollsBackMerchantAndExistingLeaderCapability() {
        for (boolean failRevoke : new boolean[]{true,false}) {
            MemberCapabilityMapper failed = mock(MemberCapabilityMapper.class, org.mockito.AdditionalAnswers.delegatesTo(capabilityMapper));
            if (failRevoke) doReturn(0).when(failed).updateStatus(eq(901L),eq(MemberCapability.CAP_CLUB_LEADER),eq(0),anyLong());
            else doReturn(0).when(failed).insertMemberCapability(any(MemberCapability.class));
            ReflectionTestUtils.setField(capabilityTarget,"memberCapabilityMapper",failed);
            try {
                assertThrows(ServiceException.class,() -> merchants.insertMmsMerchant(activeMerchant()));
                assertEquals(0,count("mms_merchant"));assertLeaderUnchanged();
            } finally {ReflectionTestUtils.setField(capabilityTarget,"memberCapabilityMapper",capabilityMapper);}
        }
    }

    @Test
    void persistedDatabaseDefaultsCannotBypassActivationAndDeletedClubsDoNotBlockIt() {
        db.execute("ALTER TABLE mms_merchant ALTER COLUMN status SET DEFAULT 1, ALTER COLUMN account_status SET DEFAULT 1");
        try {
            db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(42,901,0,0)");
            MmsMerchant requested = new MmsMerchant();requested.setMemberId(901L);requested.setName("default active");
            assertThrows(ServiceException.class,() -> merchants.insertMmsMerchant(requested));
            assertEquals(0,count("mms_merchant"));assertLeaderUnchanged();
            db.update("UPDATE club SET del_flag=2 WHERE id=42");
            assertEquals(1,merchants.insertMmsMerchant(requested));
            assertTrue(capabilities.hasCapability(901L,MemberCapability.CAP_MERCHANT));
        } finally {db.execute("ALTER TABLE mms_merchant ALTER COLUMN status SET DEFAULT 0, ALTER COLUMN account_status SET DEFAULT 0");}
    }

    @Test
    void memberDisabledOrActiveOperatorCannotActivateAndLeaveNoIdentityMutation() {
        db.update("UPDATE ums_member SET status=0 WHERE id=901");
        assertThrows(ServiceException.class, () -> merchants.insertMmsMerchant(activeMerchant()));
        assertEquals(0, count("mms_merchant")); assertLeaderUnchanged();
        db.update("UPDATE ums_member SET status=1 WHERE id=901");
        when(operators.selectActiveByMemberIdForUpdate(901L)).thenReturn(new MerchantOperator());
        assertThrows(ServiceException.class, () -> merchants.insertMmsMerchant(activeMerchant()));
        assertEquals(0, count("mms_merchant")); assertLeaderUnchanged();
    }

    @Test
    void disabledApprovalDoesNotDefaultToEnabledButExplicitEnableStillChecksEveryClub() {
        db.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(77,901,0,2,0)");
        db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(42,901,0,0)");
        for (Long account : new Long[]{null, 0L}) {
            db.update("UPDATE mms_merchant SET status=0,account_status=2 WHERE id=77");
            MmsMerchant approval = new MmsMerchant(); approval.setId(77L); approval.setStatus(1L); approval.setAccountStatus(account);
            assertEquals(1, merchants.updateMmsMerchant(approval));
            assertNotEquals(1, db.queryForObject("SELECT account_status FROM mms_merchant WHERE id=77", Integer.class));
            assertLeaderUnchanged();
        }
        MmsMerchant enable = new MmsMerchant(); enable.setId(77L); enable.setAccountStatus(1L);
        assertThrows(ServiceException.class, () -> merchants.updateMmsMerchant(enable));
        assertLeaderUnchanged();
    }

    @Test
    void activationMustSeeLeaderCapabilityGrantedAfterItsOldReadSnapshot() throws Exception {
        for (boolean alreadyRevoked : new boolean[]{false,true}) {
            seed();
            if (alreadyRevoked) capabilities.revoke(901L,MemberCapability.CAP_CLUB_LEADER);
            else db.update("DELETE FROM member_capability");
            db.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(77,901,0,0,0)");
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                transactions.execute(s -> {
                    assertFalse(capabilities.hasCapability(901L,MemberCapability.CAP_CLUB_LEADER)); // older RR snapshot
                    try {other.submit(() -> capabilities.grant(901L,MemberCapability.CAP_CLUB_LEADER,1L)).get(5,TimeUnit.SECONDS);}
                    catch(Exception e) {throw new AssertionError(e);}
                    MmsMerchant approval = new MmsMerchant();approval.setId(77L);approval.setStatus(1L);
                    merchants.updateMmsMerchant(approval);return null;
                });
            } finally {other.shutdownNow();assertTrue(other.awaitTermination(5,TimeUnit.SECONDS));}
            assertFalse(capabilities.hasCapability(901L,MemberCapability.CAP_CLUB_LEADER),"current-read revoke must see other transaction's new capability");
            assertTrue(capabilities.hasCapability(901L,MemberCapability.CAP_MERCHANT));
        }
    }

    @Test
    void internalActivationRequiresAnOuterTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> merchants.activateMerchantOwner(901L));
        assertLeaderUnchanged();
    }

    @Test
    void merchantActivationSerializesWithClubCreationInBothCommitOrders() throws Exception {
        holdWinnerWhileLoserWaits(() -> merchants.insertMmsMerchant(activeMerchant()), this::createClub);
        assertEquals(1, count("mms_merchant")); assertEquals(0, count("club"));
        seed();
        holdWinnerWhileLoserWaits(this::createClub, () -> merchants.insertMmsMerchant(activeMerchant()));
        assertEquals(0, count("mms_merchant")); assertEquals(1, count("club")); assertLeaderUnchanged();
    }

    @Test
    void merchantActivationAndTransferToSameMemberSerializeInBothCommitOrders() throws Exception {
        seedTransfer();
        holdWinnerWhileLoserWaits(() -> merchants.insertMmsMerchant(activeMerchant()), this::transfer);
        assertEquals(900L, db.queryForObject("SELECT member_id FROM club WHERE id=42",Long.class));
        assertEquals(0,count("club_governance_owner_transfer"));
        seed(); seedTransfer();
        holdWinnerWhileLoserWaits(this::transfer, () -> merchants.insertMmsMerchant(activeMerchant()));
        assertEquals(901L,db.queryForObject("SELECT member_id FROM club WHERE id=42",Long.class));
        assertEquals(0,count("mms_merchant")); assertEquals(1,count("club_governance_owner_transfer"));
        assertTrue(capabilities.hasCapability(901L,MemberCapability.CAP_CLUB_LEADER));
        assertFalse(capabilities.hasCapability(900L,MemberCapability.CAP_CLUB_LEADER));
        assertEquals(900L,db.queryForObject("SELECT member_id FROM club_member WHERE club_id=42",Long.class));
    }

    @Test
    void formerOwnerWithAnUnapprovedClubRetainsLeaderIdentityAfterOtherClubTransfer() {
        seedTransfer();
        db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(43,900,0,0)");
        transfer();
        assertTrue(capabilities.hasCapability(900L,MemberCapability.CAP_CLUB_LEADER));
        assertEquals("club",db.queryForObject("SELECT role FROM ums_member WHERE id=900",String.class));
    }

    @Test
    void claimOwnerCasAndAuditReceiptFailureRollBackBothCapabilitiesAndOwner() {
        db.update("INSERT INTO mms_merchant(id,status,account_status,del_flag) VALUES(77,1,2,0)");
        com.chengyinhub.business.domain.dto.MerchantClaimDTO.AuditRequest request = new com.chengyinhub.business.domain.dto.MerchantClaimDTO.AuditRequest();
        request.setClaimId(3L);request.setAction("APPROVE");request.setRequestId("identity-claim");request.setExpectedStatus("PENDING");request.setExpectedVersion(0);
        assertEquals("OWNER_CAS_LOST",assertThrows(com.chengyinhub.business.exception.MerchantClaimException.class,() -> claims.audit(99L,request)).getBusinessCode());
        assertNull(db.queryForObject("SELECT member_id FROM mms_merchant WHERE id=77",Long.class));assertLeaderUnchanged();
        db.update("UPDATE mms_merchant SET account_status=1 WHERE id=77");
        assertEquals("STATE_CAS_LOST",assertThrows(com.chengyinhub.business.exception.MerchantClaimException.class,() -> claims.audit(99L,request)).getBusinessCode());
        assertNull(db.queryForObject("SELECT member_id FROM mms_merchant WHERE id=77",Long.class));assertLeaderUnchanged();
    }

    @Test
    void successfulClaimUsesTheSameAtomicLeaderToMerchantSwitch() {
        db.update("INSERT INTO mms_merchant(id,status,account_status,del_flag) VALUES(77,1,1,0)");
        when(claimFacts.auditCas(anyLong(),anyString(),anyInt(),anyString(),anyLong(),any(),anyString(),anyString())).thenReturn(1);
        com.chengyinhub.business.domain.dto.MerchantClaimDTO.AuditRequest request = new com.chengyinhub.business.domain.dto.MerchantClaimDTO.AuditRequest();
        request.setClaimId(3L);request.setAction("APPROVE");request.setRequestId("identity-claim-success");request.setExpectedStatus("PENDING");request.setExpectedVersion(0);
        claims.audit(99L,request);
        assertEquals(901L,db.queryForObject("SELECT member_id FROM mms_merchant WHERE id=77",Long.class));
        assertFalse(capabilities.hasCapability(901L,MemberCapability.CAP_CLUB_LEADER));
        assertTrue(capabilities.hasCapability(901L,MemberCapability.CAP_MERCHANT));
        assertEquals("merchant",db.queryForObject("SELECT role FROM ums_member WHERE id=901",String.class));
        assertEquals(1,count("club_leader"));
    }

    @Test
    void approvalReferralUsesExistingReasonAmountAndDoesNotRepeatOnEditOrReenable() {
        prepareReferral(false);
        MmsMerchant approval = new MmsMerchant();approval.setId(77L);approval.setStatus(1L);
        assertEquals(1,merchants.updateMmsMerchant(approval));
        assertEquals(117L,db.queryForObject("SELECT point FROM ums_member WHERE id=900",Long.class));
        assertEquals(1,count("identity_points_receipt"));
        assertEquals("推荐商户注册",db.queryForObject("SELECT change_reason FROM identity_points_receipt",String.class));
        assertEquals(17L,db.queryForObject("SELECT change_points FROM identity_points_receipt",Long.class));
        MmsMerchant edit = new MmsMerchant();edit.setId(77L);edit.setName("资料修改");merchants.updateMmsMerchant(edit);
        MmsMerchant disable = new MmsMerchant();disable.setId(77L);disable.setAccountStatus(2L);merchants.updateMmsMerchant(disable);
        MmsMerchant enable = new MmsMerchant();enable.setId(77L);enable.setAccountStatus(1L);merchants.updateMmsMerchant(enable);
        assertEquals(117L,db.queryForObject("SELECT point FROM ums_member WHERE id=900",Long.class));
        assertEquals(1,count("identity_points_receipt"));
    }

    @Test
    void referralReceiptZeroRowsRollsBackApprovalBalanceAndIdentity() {
        prepareReferral(true);
        MmsMerchant approval = new MmsMerchant();approval.setId(77L);approval.setStatus(1L);
        assertThrows(ServiceException.class,() -> merchants.updateMmsMerchant(approval));
        assertEquals(100L,db.queryForObject("SELECT point FROM ums_member WHERE id=900",Long.class));
        assertEquals(0,count("identity_points_receipt"));
        assertEquals(0,db.queryForObject("SELECT status FROM mms_merchant WHERE id=77",Integer.class));
        assertEquals(0,db.queryForObject("SELECT account_status FROM mms_merchant WHERE id=77",Integer.class));
        assertLeaderUnchanged();
    }

    @Test
    void ownerProfileWriteWaitsOnMemberBeforeMerchantDuringConcurrentDisable() throws Exception {
        merchants.insertMmsMerchant(activeMerchant());
        Long id = db.queryForObject("SELECT id FROM mms_merchant",Long.class);
        UmsMemberMapper observed = mock(UmsMemberMapper.class,org.mockito.AdditionalAnswers.delegatesTo(members));
        CountDownLatch requestedMember = new CountDownLatch(1);
        doAnswer(c -> {requestedMember.countDown();return members.lockActiveMemberById(c.getArgument(0));})
                .when(observed).lockActiveMemberById(901L);
        ReflectionTestUtils.setField(merchantTarget,"umsMemberMapper",observed);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Future<?>> profile = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            transactions.execute(tx -> {
                members.lockMemberById(901L);
                profile.set(pool.submit(() -> {MmsMerchant patch = new MmsMerchant();patch.setId(id);patch.setName("经营编辑");
                    merchants.updateAuthorizedProfile(patch,901L,MerchantPermission.PROFILE_WRITE);}));
                await(requestedMember);
                MmsMerchant disable = new MmsMerchant();disable.setId(id);disable.setAccountStatus(2L);
                merchants.updateMmsMerchant(disable);return null;
            });
            ExecutionException rejected = assertThrows(ExecutionException.class,() -> profile.get().get(5,TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof ServiceException,String.valueOf(rejected.getCause()));
            assertEquals(2,db.queryForObject("SELECT account_status FROM mms_merchant WHERE id=?",Integer.class,id));
            assertEquals("active store",db.queryForObject("SELECT name FROM mms_merchant WHERE id=?",String.class,id));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test
    void sameStoreEmployeeCanBecomeOwnerAfterDelegationsAreRevokedAtomically() {
        prepareOwnerChange(77L);
        MmsMerchant change = new MmsMerchant();change.setId(77L);change.setMemberId(901L);
        assertEquals(1,merchants.updateMmsMerchant(change));
        assertEquals(901L,db.queryForObject("SELECT member_id FROM mms_merchant WHERE id=77",Long.class));
        assertEquals("REVOKED",db.queryForObject("SELECT status FROM merchant_operator WHERE id=1",String.class));
        assertNull(db.queryForObject("SELECT active_operator_member_id FROM merchant_operator WHERE id=1",Long.class));
        assertEquals("REVOKED",db.queryForObject("SELECT status FROM merchant_operator_invite WHERE id=1",String.class));
        assertFalse(capabilities.hasCapability(901L,MemberCapability.CAP_CLUB_LEADER));
        assertTrue(capabilities.hasCapability(901L,MemberCapability.CAP_MERCHANT));
    }

    @Test
    void otherStoreEmployeeOrUntransferredClubRejectOwnerChangeAndRestoreOldDelegations() {
        for (boolean otherStore : new boolean[]{true,false}) {
            seed();prepareOwnerChange(otherStore ? 88L : 77L);
            if (!otherStore) db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(43,901,0,0)");
            db.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,role_code,status) VALUES(2,77,902,'MERCHANT_CHECKIN','ACTIVE')");
            MmsMerchant change = new MmsMerchant();change.setId(77L);change.setMemberId(901L);
            assertThrows(ServiceException.class,() -> merchants.updateMmsMerchant(change));
            assertEquals(900L,db.queryForObject("SELECT member_id FROM mms_merchant WHERE id=77",Long.class));
            assertEquals(2,db.queryForObject("SELECT count(*) FROM merchant_operator WHERE status='ACTIVE'",Integer.class));
            assertEquals("PENDING",db.queryForObject("SELECT status FROM merchant_operator_invite WHERE id=1",String.class));
            assertLeaderUnchanged();
        }
    }

    private void prepareOwnerChange(Long candidateStore) {
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag) VALUES(900,'merchant',2,1,0)");
        db.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(77,900,1,1,0)");
        db.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,role_code,status) VALUES(1,?,901,'MERCHANT_MANAGER','ACTIVE')",candidateStore);
        db.update("INSERT INTO merchant_operator_invite(id,merchant_id,status,token_hash) VALUES(1,77,'PENDING','old-token-hash')");
    }

    private void prepareReferral(boolean receiptFails) {
        db.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(77,901,0,0,0)");
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag,point) VALUES(900,'player',1,1,0,100)");
        IUmsMemberService accounts = mock(IUmsMemberService.class);
        UmsMember owner = new UmsMember();owner.setId(901L);owner.setInviteUserId("900");
        UmsMember inviter = new UmsMember();inviter.setId(900L);inviter.setPoint(100L);
        when(accounts.selectUmsMemberById(901L)).thenReturn(owner);
        when(accounts.selectUmsMemberById(900L)).thenReturn(inviter);
        when(accounts.addPoints(eq(900L),anyInt())).thenAnswer(c -> db.update("UPDATE ums_member SET point=point+? WHERE id=900",(Integer)c.getArgument(1))==1);
        IUmsMemberPointsDetailService details = mock(IUmsMemberPointsDetailService.class);
        when(details.selectUmsMemberPointsDetailList(any())).thenAnswer(c -> {
            UmsMemberPointsDetail query = c.getArgument(0);assertEquals("推荐商户注册",query.getChangeReason());assertEquals(901L,query.getEventId());
            return java.util.Collections.emptyList();
        });
        when(details.insertUmsMemberPointsDetail(any())).thenAnswer(c -> {
            if (receiptFails) return 0;
            UmsMemberPointsDetail row = c.getArgument(0);
            return db.update("INSERT INTO identity_points_receipt(event_id,change_points,change_reason) VALUES(?,?,?)",row.getEventId(),row.getChangePoints(),row.getChangeReason());
        });
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        CmsPointsSetting setting = new CmsPointsSetting();setting.setValue(new java.math.BigDecimal("17"));
        when(settings.selectCmsPointsSettingById(7L)).thenReturn(setting);
        ReflectionTestUtils.setField(merchantTarget,"umsMemberService",accounts);
        ReflectionTestUtils.setField(merchantTarget,"umsMemberPointsDetailService",details);
        ReflectionTestUtils.setField(merchantTarget,"cmsPointsSettingService",settings);
    }

    private void seedTransfer() {
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag,update_time) VALUES(900,'club',3,1,0,NULL)");
        capabilities.grant(900L,MemberCapability.CAP_CLUB_LEADER,1L);
        db.update("INSERT INTO club(id,member_id,status,del_flag) VALUES(42,900,1,0)");
        db.update("INSERT INTO club_member(club_id,member_id,role,status,del_flag) VALUES(42,901,0,1,0)");
    }
    private void transfer() {governance.transferOwner(900L,42L,901L,"移交主理人","identity-transfer");}

    private void holdWinnerWhileLoserWaits(Runnable winner, Runnable loser) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> transactions.execute(s -> {winner.run(); written.countDown(); await(release); return null;}));
            assertTrue(written.await(5, TimeUnit.SECONDS), "winning transaction must reach actual SQL");
            Future<?> second = pool.submit(() -> {started.countDown(); loser.run();});
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS), "competing write must wait for shared member lock");
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            ExecutionException rejected = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof ServiceException, String.valueOf(rejected.getCause()));
        } finally {release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));}
    }
    private void createClub() {Club c = new Club(); c.setMemberId(901L); c.setName("new club"); c.setStatus(0L); c.setDelFlag(0); clubs.insertClubWithinOwnerQuota(c,2);}
    private void assertLeaderUnchanged() {
        assertTrue(capabilities.hasCapability(901L, MemberCapability.CAP_CLUB_LEADER));
        assertFalse(capabilities.hasCapability(901L, MemberCapability.CAP_MERCHANT));
        assertEquals("club", db.queryForObject("SELECT role FROM ums_member WHERE id=901", String.class));
        assertEquals(1, count("club_leader"));
    }
    private int count(String table) {return db.queryForObject("SELECT count(*) FROM " + table,Integer.class);}
    private MmsMerchant activeMerchant() {MmsMerchant m = new MmsMerchant();m.setMemberId(901L);m.setName("active store");m.setStatus(1L);m.setAccountStatus(1L);m.setDelFlag(0);return m;}
    private Object proxy(Object target) {ProxyFactory p = new ProxyFactory(target);p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return p.getProxy();}
    private static void await(CountDownLatch latch) {try {if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("transaction barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static final String MERCHANT_DDL = "CREATE TABLE mms_merchant ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT UNIQUE, name VARCHAR(255), wechat VARCHAR(255),"
                    + "category_id INT, logo VARCHAR(500), description TEXT, derivatives TEXT, derivative_benefits VARCHAR(100), website VARCHAR(255),"
                    + "preference VARCHAR(255), phone VARCHAR(50), address VARCHAR(255), business_time VARCHAR(255),"
                    + "business_license VARCHAR(255), status BIGINT DEFAULT 0, reson VARCHAR(255), disable_reason VARCHAR(500), account_status BIGINT DEFAULT 0,"
                    + "business_status INT, del_flag INT DEFAULT 0, capacity INT, available_time VARCHAR(255),"
                    + "suit_activity_types VARCHAR(255), charge_type INT, demand TEXT, coop_open INT,"
                    + "cover_image VARCHAR(500), slogan VARCHAR(255), city_role VARCHAR(255), story_title VARCHAR(255),"
                    + "gallery TEXT, tags VARCHAR(500), featured_type INT, featured_id BIGINT,"
                    + "service_tag VARCHAR(8), service_text VARCHAR(64),"
                    + "location_lat DOUBLE, location_lng DOUBLE, location_verified INT,"
                    + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)";
}
