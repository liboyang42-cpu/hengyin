package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubGovernanceOwnerTransfer;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubGovernanceServiceImpl;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 真实 H2 事务/行锁并发合同：创建与转让必须共用 ums_member 锁，旧 owner 收口必须读提交后的当前归属。
 * Mapper mock 只把生产窄查询映射到真实 JDBC；事务边界、服务调用顺序和竞争均走生产 Service。
 */
class ClubOwnerQuotaConcurrencyTransactionTest {
    private static final long OWNER = 900L;
    private static final long OWNER_B = 901L;
    private static final long TARGET_A = 902L;
    private static final long TARGET_B = 903L;
    private static final long CLUB_A = 11L;
    private static final long CLUB_B = 14L;

    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final AtomicLong clubIds = new AtomicLong(100L);
    private final AtomicLong transferIds = new AtomicLong(200L);
    private final Map<Long, UmsMember> accounts = new ConcurrentHashMap<>();
    private final Map<Long, ClubLeader> leaders = new ConcurrentHashMap<>();
    private final Set<Long> clubLeaderCapabilities = ConcurrentHashMap.newKeySet();

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IUmsMemberService members;
    private ClubMapper clubs;
    private ClubGovernanceMapper governance;
    private ICapabilityService capabilities;
    private IClubService clubService;
    private ClubGovernanceService governanceService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_owner_quota_race;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table ums_member(id bigint primary key)");
        jdbc.execute("create table club(id bigint primary key, member_id bigint not null, name varchar(80), "
                + "status bigint not null, del_flag int not null, create_time timestamp)");
        jdbc.update("insert into ums_member(id) values (?),(?),(?),(?)",
                OWNER, OWNER_B, TARGET_A, TARGET_B);

        accounts.put(OWNER, account(OWNER, IRoleService.ROLE_CLUB));
        accounts.put(OWNER_B, account(OWNER_B, IRoleService.ROLE_CLUB));
        accounts.put(TARGET_A, account(TARGET_A, IRoleService.ROLE_PLAYER));
        accounts.put(TARGET_B, account(TARGET_B, IRoleService.ROLE_PLAYER));
        clubLeaderCapabilities.add(OWNER);
        clubLeaderCapabilities.add(OWNER_B);
        leaders.put(TARGET_A, activeLeader(TARGET_A));

        members = dataBackedMembers();
        clubs = dataBackedClubs();
        governance = dataBackedGovernance();
        capabilities = stateBackedCapabilities();
        clubService = proxiedClubService();
        governanceService = proxiedGovernanceService();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void createAndTwoTransfersShareTargetMemberLockAndCannotExceedTwoOwnedClubs() throws Exception {
        insertClub(CLUB_A, OWNER);
        insertClub(CLUB_B, OWNER_B);
        Club newClub = club(15L, TARGET_A);
        CyclicBarrier competitorsReady = new CyclicBarrier(3);

        Future<String> create = workers.submit(() -> outcomeAfterBarrier(competitorsReady,
                () -> clubService.insertClubWithinOwnerQuota(newClub, 2)));
        Future<String> firstTransfer = workers.submit(() -> outcomeAfterBarrier(competitorsReady,
                () -> governanceService.transferOwner(
                        OWNER, CLUB_A, TARGET_A, "交接", "transfer-create-race")));
        Future<String> secondTransfer = workers.submit(() -> outcomeAfterBarrier(competitorsReady,
                () -> governanceService.transferOwner(
                        OWNER_B, CLUB_B, TARGET_A, "交接", "transfer-transfer-race")));

        List<String> outcomes = java.util.Arrays.asList(
                create.get(10, TimeUnit.SECONDS),
                firstTransfer.get(10, TimeUnit.SECONDS),
                secondTransfer.get(10, TimeUnit.SECONDS));
        int successCount = (int) outcomes.stream().filter("OK"::equals).count();
        assertEquals(2, successCount,
                "三个并发写都会增加 target 的 club 数；共享 member 锁后第三个必须被配额拒绝");
        String rejection = outcomes.stream().filter(value -> !"OK".equals(value)).findFirst().orElse("");
        assertTrue(rejection.contains("上限") || rejection.contains("最多创建"), rejection);
        assertEquals(2, ownedClubCount(TARGET_A));
        verify(members, atLeast(3)).lockMemberById(TARGET_A);
    }

    @Test
    void concurrentTransfersSerializeFormerOwnerAndLastCommitRevokesOnlyAfterCurrentReadIsEmpty()
            throws Exception {
        insertClub(CLUB_A, OWNER);
        insertClub(CLUB_B, OWNER);
        CyclicBarrier competitorsReady = new CyclicBarrier(2);

        Future<Boolean> first = workers.submit(() -> afterBarrier(competitorsReady,
                () -> governanceService.transferOwner(
                        OWNER, CLUB_A, TARGET_A, "交接 A", "transfer-a")));
        Future<Boolean> second = workers.submit(() -> afterBarrier(competitorsReady,
                () -> governanceService.transferOwner(
                        OWNER, CLUB_B, TARGET_B, "交接 B", "transfer-b")));

        assertTrue(first.get(10, TimeUnit.SECONDS));
        assertTrue(second.get(10, TimeUnit.SECONDS));
        assertEquals(0, activeOwnedClubCount(OWNER));
        assertFalse(clubLeaderCapabilities.contains(OWNER));
        assertEquals(IRoleService.ROLE_PLAYER, accounts.get(OWNER).getRole());
        verify(members, atLeast(2)).lockMemberById(OWNER);
        verify(capabilities, times(1)).revoke(OWNER, MemberCapability.CAP_CLUB_LEADER);
    }

    private IClubService proxiedClubService() {
        ClubServiceImpl target = new ClubServiceImpl();
        ClubLeaderMapper leaderMapper = mock(ClubLeaderMapper.class);
        when(leaderMapper.selectClubLeaderByMemberId(anyLong()))
                .thenAnswer(call -> leaders.get(call.getArgument(0, Long.class)));
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubLeaderMapper", leaderMapper);
        ReflectionTestUtils.setField(target, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(target, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        return transactionalProxy(target, IClubService.class);
    }

    private ClubGovernanceService proxiedGovernanceService() {
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.require(anyLong(), anyLong(), isNull(), eq(ClubPermission.MEMBER_MANAGE)))
                .thenAnswer(call -> ownerAccess(
                        call.getArgument(0, Long.class), call.getArgument(1, Long.class)));

        ClubMemberMapper clubMembers = mock(ClubMemberMapper.class);
        when(clubMembers.selectByClubAndMemberForUpdate(anyLong(), anyLong()))
                .thenAnswer(call -> activeMember(
                        call.getArgument(0, Long.class), call.getArgument(1, Long.class)));

        IClubLeaderService leaderService = mock(IClubLeaderService.class);
        when(leaderService.selectClubLeaderByMemberId(anyLong()))
                .thenAnswer(call -> leaders.get(call.getArgument(0, Long.class)));
        when(leaderService.insertClubLeader(any(ClubLeader.class))).thenAnswer(call -> {
            ClubLeader row = call.getArgument(0, ClubLeader.class);
            leaders.putIfAbsent(row.getMemberId(), row);
            return 1;
        });

        IRoleService roles = mock(IRoleService.class);
        when(roles.resolveRole(any(UmsMember.class)))
                .thenAnswer(call -> call.getArgument(0, UmsMember.class).getRole());

        IMerchantAccessService merchantAccess = mock(IMerchantAccessService.class);
        IClubRoleAssignmentService roleAssignments = mock(IClubRoleAssignmentService.class);
        when(roleAssignments.revokeAllForAccessEnd(
                anyLong(), anyLong(), anyLong(), any(String.class), any(String.class))).thenReturn(1);

        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);

        ClubGovernanceServiceImpl target = new ClubGovernanceServiceImpl();
        ReflectionTestUtils.setField(target, "clubAccessService", access);
        ReflectionTestUtils.setField(target, "clubMapper", clubs);
        ReflectionTestUtils.setField(target, "clubMemberMapper", clubMembers);
        ReflectionTestUtils.setField(target, "clubRoleAssignmentService", roleAssignments);
        ReflectionTestUtils.setField(target, "clubLeaderService", leaderService);
        ReflectionTestUtils.setField(target, "capabilityService", capabilities);
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        ReflectionTestUtils.setField(target, "roleService", roles);
        ReflectionTestUtils.setField(target, "merchantAccessService", merchantAccess);
        ReflectionTestUtils.setField(target, "clubGovernanceMapper", governance);
        ReflectionTestUtils.setField(target, "businessAuditEventMapper", audits);
        return transactionalProxy(target, ClubGovernanceService.class);
    }

    private IUmsMemberService dataBackedMembers() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        when(service.lockMemberById(anyLong())).thenAnswer(call -> {
            Long memberId = call.getArgument(0, Long.class);
            List<Long> rows = jdbc.queryForList(
                    "select id from ums_member where id=? for update", Long.class, memberId);
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(service.selectUmsMemberById(anyLong()))
                .thenAnswer(call -> accounts.get(call.getArgument(0, Long.class)));
        when(service.updateUmsMember(any(UmsMember.class))).thenAnswer(call -> {
            UmsMember patch = call.getArgument(0, UmsMember.class);
            return accounts.computeIfPresent(patch.getId(), (id, current) -> {
                if (patch.getRole() != null) current.setRole(patch.getRole());
                return current;
            }) == null ? 0 : 1;
        });
        return service;
    }

    private ClubMapper dataBackedClubs() {
        ClubMapper mapper = mock(ClubMapper.class);
        when(mapper.selectClubByIdForUpdate(anyLong())).thenAnswer(call -> lockedClub(
                call.getArgument(0, Long.class)));
        when(mapper.selectOwnedClubIdsForUpdate(anyLong())).thenAnswer(call -> ownedClubIdsForUpdate(
                call.getArgument(0, Long.class), false));
        when(mapper.selectActiveOwnedClubIdsForUpdate(anyLong())).thenAnswer(call -> ownedClubIdsForUpdate(
                call.getArgument(0, Long.class), true));
        when(mapper.insertClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            if (row.getId() == null) row.setId(clubIds.incrementAndGet());
            return jdbc.update("insert into club(id,member_id,name,status,del_flag,create_time) "
                            + "values (?,?,?,?,?,?)", row.getId(), row.getMemberId(), row.getName(),
                    row.getStatus(), row.getDelFlag(), row.getCreateTime());
        });
        return mapper;
    }

    private ClubGovernanceMapper dataBackedGovernance() {
        ClubGovernanceMapper mapper = mock(ClubGovernanceMapper.class);
        when(mapper.insertOwnerTransfer(any(ClubGovernanceOwnerTransfer.class))).thenAnswer(call -> {
            call.getArgument(0, ClubGovernanceOwnerTransfer.class)
                    .setId(transferIds.incrementAndGet());
            return 1;
        });
        when(mapper.deleteActiveMemberForOwnerTransfer(anyLong(), anyLong())).thenReturn(1);
        when(mapper.upsertFormerOwnerAsMember(anyLong(), anyLong(), any())).thenReturn(1);
        when(mapper.transferClubOwnerCas(anyLong(), anyLong(), anyLong())).thenAnswer(call -> jdbc.update(
                "update club set member_id=? where id=? and member_id=? and status=1 and del_flag=0",
                call.getArgument(2, Long.class), call.getArgument(0, Long.class),
                call.getArgument(1, Long.class)));
        return mapper;
    }

    private ICapabilityService stateBackedCapabilities() {
        ICapabilityService service = mock(ICapabilityService.class);
        when(service.hasCapability(anyLong(), eq(MemberCapability.CAP_MERCHANT))).thenReturn(false);
        org.mockito.Mockito.doAnswer(call -> {
            clubLeaderCapabilities.add(call.getArgument(0, Long.class));
            return null;
        }).when(service).grant(anyLong(), eq(MemberCapability.CAP_CLUB_LEADER), anyLong());
        org.mockito.Mockito.doAnswer(call -> {
            clubLeaderCapabilities.remove(call.getArgument(0, Long.class));
            return null;
        }).when(service).revoke(anyLong(), eq(MemberCapability.CAP_CLUB_LEADER));
        return service;
    }

    private Club lockedClub(Long clubId) {
        List<Club> rows = jdbc.query(
                "select id,member_id,name,status,del_flag from club where id=? for update",
                (rs, rowNum) -> {
                    Club row = new Club();
                    row.setId(rs.getLong("id"));
                    row.setMemberId(rs.getLong("member_id"));
                    row.setName(rs.getString("name"));
                    row.setStatus(rs.getLong("status"));
                    row.setDelFlag(rs.getInt("del_flag"));
                    return row;
                }, clubId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Long> ownedClubIdsForUpdate(Long memberId, boolean activeOnly) {
        String sql = "select id from club where member_id=? and del_flag=0"
                + (activeOnly ? " and status=1" : "") + " order by id for update";
        return jdbc.queryForList(sql, Long.class, memberId);
    }

    private int ownedClubCount(Long memberId) {
        return jdbc.queryForObject(
                "select count(*) from club where member_id=? and del_flag=0", Integer.class, memberId);
    }

    private int activeOwnedClubCount(Long memberId) {
        return jdbc.queryForObject(
                "select count(*) from club where member_id=? and status=1 and del_flag=0",
                Integer.class, memberId);
    }

    private void insertClub(Long clubId, Long ownerMemberId) {
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (?,?,?,1,0)",
                clubId, ownerMemberId, "club-" + clubId);
    }

    private Club club(Long id, Long ownerMemberId) {
        Club row = new Club();
        row.setId(id);
        row.setMemberId(ownerMemberId);
        row.setName("club-" + id);
        row.setStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private UmsMember account(Long id, String role) {
        UmsMember row = new UmsMember();
        row.setId(id);
        row.setNickname("member-" + id);
        row.setStatus(1L);
        row.setDelFlag(0);
        row.setRole(role);
        return row;
    }

    private ClubLeader activeLeader(Long memberId) {
        ClubLeader row = new ClubLeader();
        row.setId(700L + memberId);
        row.setMemberId(memberId);
        row.setStatus(1);
        row.setDelFlag(0);
        return row;
    }

    private ClubMember activeMember(Long clubId, Long memberId) {
        ClubMember row = new ClubMember();
        row.setId(clubId * 1000 + memberId);
        row.setClubId(clubId);
        row.setMemberId(memberId);
        row.setStatus(1);
        row.setDelFlag(0);
        return row;
    }

    private ClubAccessContext ownerAccess(Long ownerMemberId, Long clubId) {
        return new ClubAccessContext(ownerMemberId, clubId, ownerMemberId, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER));
    }

    private boolean afterBarrier(CyclicBarrier competitorsReady, CheckedOperation operation) throws Exception {
        competitorsReady.await(5, TimeUnit.SECONDS);
        try {
            operation.run();
            return true;
        } catch (ServiceException expectedBusinessConflict) {
            return false;
        }
    }

    private String outcomeAfterBarrier(CyclicBarrier competitorsReady, CheckedOperation operation) throws Exception {
        competitorsReady.await(5, TimeUnit.SECONDS);
        try {
            operation.run();
            return "OK";
        } catch (ServiceException expectedBusinessConflict) {
            return expectedBusinessConflict.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> contract) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(contract);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
