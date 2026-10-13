package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MerchantOperatorInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.business.service.impl.MerchantOperatorServiceImpl;
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

import java.util.Date;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实事务负控：商家员工与俱乐部主理人身份必须争用同一 ums_member 行锁。 */
class MerchantClubIdentityMutexConcurrencyTest {
    private static final long MEMBER_ID = 901L;
    private static final long MERCHANT_ID = 77L;
    private static final String TOKEN = "identity-race-token-1234567890";

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicLong ids = new AtomicLong(100L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IClubService clubs;
    private IMerchantOperatorService operators;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:merchant_club_identity_mutex;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table ums_member(id bigint primary key)");
        jdbc.execute("create table club_leader(id bigint primary key, member_id bigint not null, "
                + "status int not null, del_flag int not null)");
        jdbc.execute("create table club(id bigint primary key, member_id bigint not null, "
                + "name varchar(80), status bigint not null, del_flag int not null, create_time timestamp)");
        jdbc.execute("create table merchant_operator(id bigint primary key, merchant_id bigint not null, "
                + "operator_member_id bigint not null, role_code varchar(40), status varchar(20))");
        jdbc.update("insert into ums_member(id) values (?)", MEMBER_ID);

        ClubLeaderMapper leaderMapper = leaderMapper();
        ClubMapper clubMapper = clubMapper();
        MerchantOperatorMapper operatorMapper = operatorMapper();
        MmsMerchantMapper merchantMapper = merchantMapper();
        IUmsMemberService memberService = memberService();

        ClubServiceImpl clubTarget = new ClubServiceImpl();
        ReflectionTestUtils.setField(clubTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(clubTarget, "clubLeaderMapper", leaderMapper);
        ReflectionTestUtils.setField(clubTarget, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(clubTarget, "merchantOperatorMapper", operatorMapper);
        ReflectionTestUtils.setField(clubTarget, "umsMemberService", memberService);
        ReflectionTestUtils.setField(clubTarget, "capabilityService", mock(ICapabilityService.class));
        IRoleService roles = mock(IRoleService.class);
        when(roles.resolveRole(any(UmsMember.class))).thenReturn(IRoleService.ROLE_CLUB);
        ReflectionTestUtils.setField(clubTarget, "roleService", roles);
        clubs = transactionalProxy(clubTarget, IClubService.class);

        MerchantOperatorInviteMapper inviteMapper = mock(MerchantOperatorInviteMapper.class);
        when(inviteMapper.selectByTokenHash(any(String.class))).thenReturn(pendingInvite());
        when(inviteMapper.selectByTokenHashForUpdate(any(String.class))).thenReturn(pendingInvite());
        when(inviteMapper.acceptCas(31L, 0, MEMBER_ID)).thenReturn(1);
        BusinessAuditEventMapper audits = mock(BusinessAuditEventMapper.class);
        when(audits.insertBusinessAuditEvent(any(BusinessAuditEvent.class))).thenReturn(1);
        MerchantOperatorServiceImpl operatorTarget = new MerchantOperatorServiceImpl();
        ReflectionTestUtils.setField(operatorTarget, "merchantAccessService", mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(operatorTarget, "merchantOperatorMapper", operatorMapper);
        ReflectionTestUtils.setField(operatorTarget, "merchantOperatorInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(operatorTarget, "businessAuditEventMapper", audits);
        ReflectionTestUtils.setField(operatorTarget, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(operatorTarget, "umsMemberMapper", memberMapper());
        ReflectionTestUtils.setField(operatorTarget, "clubLeaderMapper", leaderMapper);
        ReflectionTestUtils.setField(operatorTarget, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(operatorTarget, "tokenSecret", "identity-race-secret");
        operators = transactionalProxy(operatorTarget, IMerchantOperatorService.class);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void concurrentOnboardingAndMerchantInviteAcceptanceCommitExactlyOneIdentity() throws Exception {
        CyclicBarrier ready = new CyclicBarrier(2);
        ClubLeader profile = new ClubLeader();
        profile.setLeaderName("并发主理人");
        Club club = new Club();
        club.setMemberId(MEMBER_ID);
        club.setName("原子首团");
        club.setStatus(1L);
        club.setDelFlag(0);

        Future<String> clubClaim = workers.submit(() -> afterBarrier(ready,
                () -> clubs.onboardLeaderAndInsertClubWithinIdentityMutex(MEMBER_ID, profile, club, 2)));
        Future<String> merchantClaim = workers.submit(() -> afterBarrier(ready,
                () -> operators.accept(MEMBER_ID, TOKEN, "identity-race")));

        List<String> outcomes = java.util.Arrays.asList(
                clubClaim.get(10, TimeUnit.SECONDS), merchantClaim.get(10, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter("OK"::equals).count(), outcomes.toString());
        int leaderCount = count("club_leader");
        int clubCount = count("club");
        int operatorCount = count("merchant_operator");
        assertTrue((leaderCount == 1 && clubCount == 1 && operatorCount == 0)
                        || (leaderCount == 0 && clubCount == 0 && operatorCount == 1),
                "入驻档案与首团必须原子提交，且不得与商家员工身份并存");
    }

    private ClubLeaderMapper leaderMapper() {
        ClubLeaderMapper mapper = mock(ClubLeaderMapper.class);
        when(mapper.selectClubLeaderByMemberId(anyLong())).thenAnswer(call -> {
            List<ClubLeader> rows = jdbc.query(
                    "select id,member_id,status,del_flag from club_leader where member_id=?",
                    (rs, n) -> {
                        ClubLeader row = new ClubLeader();
                        row.setId(rs.getLong("id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setStatus(rs.getInt("status"));
                        row.setDelFlag(rs.getInt("del_flag"));
                        return row;
                    }, call.getArgument(0, Long.class));
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(mapper.insertClubLeader(any(ClubLeader.class))).thenAnswer(call -> {
            ClubLeader row = call.getArgument(0, ClubLeader.class);
            row.setId(ids.incrementAndGet());
            return jdbc.update("insert into club_leader(id,member_id,status,del_flag) values (?,?,?,?)",
                    row.getId(), row.getMemberId(), row.getStatus(), row.getDelFlag());
        });
        return mapper;
    }

    private ClubMapper clubMapper() {
        ClubMapper mapper = mock(ClubMapper.class);
        when(mapper.selectOwnedClubIdsForUpdate(anyLong())).thenAnswer(call -> jdbc.queryForList(
                "select id from club where member_id=? and del_flag=0 order by id for update",
                Long.class, call.getArgument(0, Long.class)));
        when(mapper.selectActiveOwnedClubIdsForUpdate(anyLong())).thenAnswer(call -> jdbc.queryForList(
                "select id from club where member_id=? and status=1 and del_flag=0 order by id for update",
                Long.class, call.getArgument(0, Long.class)));
        when(mapper.insertClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            row.setId(ids.incrementAndGet());
            return jdbc.update("insert into club(id,member_id,name,status,del_flag,create_time) values (?,?,?,?,?,?)",
                    row.getId(), row.getMemberId(), row.getName(), row.getStatus(), row.getDelFlag(), row.getCreateTime());
        });
        return mapper;
    }

    private MerchantOperatorMapper operatorMapper() {
        MerchantOperatorMapper mapper = mock(MerchantOperatorMapper.class);
        when(mapper.selectActiveByMemberId(anyLong())).thenAnswer(call -> {
            List<MerchantOperator> rows = jdbc.query(
                    "select id,merchant_id,operator_member_id,role_code,status from merchant_operator "
                            + "where operator_member_id=? and status='ACTIVE'",
                    (rs, n) -> {
                        MerchantOperator row = new MerchantOperator();
                        row.setId(rs.getLong("id"));
                        row.setMerchantId(rs.getLong("merchant_id"));
                        row.setOperatorMemberId(rs.getLong("operator_member_id"));
                        row.setRoleCode(rs.getString("role_code"));
                        row.setStatus(rs.getString("status"));
                        return row;
                    }, call.getArgument(0, Long.class));
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(mapper.insertMerchantOperator(any(MerchantOperator.class))).thenAnswer(call -> {
            MerchantOperator row = call.getArgument(0, MerchantOperator.class);
            row.setId(ids.incrementAndGet());
            return jdbc.update("insert into merchant_operator(id,merchant_id,operator_member_id,role_code,status) "
                            + "values (?,?,?,?,?)", row.getId(), row.getMerchantId(), row.getOperatorMemberId(),
                    row.getRoleCode(), row.getStatus());
        });
        return mapper;
    }

    private MmsMerchantMapper merchantMapper() {
        MmsMerchantMapper mapper = mock(MmsMerchantMapper.class);
        when(mapper.selectMmsMerchantByIdForUpdate(MERCHANT_ID)).thenReturn(activeMerchant());
        return mapper;
    }

    private IUmsMemberService memberService() {
        IUmsMemberService service = mock(IUmsMemberService.class);
        when(service.lockMemberById(MEMBER_ID)).thenAnswer(call -> lockMember());
        when(service.selectUmsMemberById(MEMBER_ID)).thenReturn(activeMember());
        return service;
    }

    private UmsMemberMapper memberMapper() {
        UmsMemberMapper mapper = mock(UmsMemberMapper.class);
        when(mapper.lockMemberById(MEMBER_ID)).thenAnswer(call -> lockMember());
        when(mapper.lockActiveMemberById(MEMBER_ID)).thenAnswer(call -> lockMember());
        when(mapper.selectUmsMemberById(MEMBER_ID)).thenReturn(activeMember());
        return mapper;
    }

    private Long lockMember() {
        List<Long> rows = jdbc.queryForList(
                "select id from ums_member where id=? for update", Long.class, MEMBER_ID);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UmsMember activeMember() {
        UmsMember row = new UmsMember();
        row.setId(MEMBER_ID);
        row.setStatus(1L);
        row.setDelFlag(0);
        row.setRole(IRoleService.ROLE_CLUB);
        row.setNickname("identity-race");
        return row;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant row = new MmsMerchant();
        row.setId(MERCHANT_ID);
        row.setMemberId(900L);
        row.setStatus(1L);
        row.setAccountStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private MerchantOperatorInvite pendingInvite() {
        MerchantOperatorInvite row = new MerchantOperatorInvite();
        row.setId(31L);
        row.setMerchantId(MERCHANT_ID);
        row.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        row.setStatus(MerchantOperatorInvite.STATUS_PENDING);
        row.setInvitedByMemberId(900L);
        row.setVersion(0);
        row.setExpiresAt(new Date(System.currentTimeMillis() + 60_000L));
        return row;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String afterBarrier(CyclicBarrier ready, CheckedOperation operation) throws Exception {
        ready.await(5, TimeUnit.SECONDS);
        try {
            operation.run();
            return "OK";
        } catch (ServiceException expected) {
            return expected.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> contract) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(contract);
        factory.addAdvice(new TransactionInterceptor(
                transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
