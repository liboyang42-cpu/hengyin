package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
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

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V5a SC-CL-CREATE:同一建团请求重发(网络重试)或两条并发,只落一行;重试原样回放那一条。
 * 与 {@link ClubOwnerQuotaConcurrencyTransactionTest} 同法:真实 H2 事务与行锁,Mapper 只把生产窄查询映射到 JDBC,
 * 竞争走生产 Service 的 ums_member 锁。
 */
class ClubCreateRetryIdempotencyConcurrencyTest {
    private static final long OWNER = 900L;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicLong clubIds = new AtomicLong(100L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_create_retry_race;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table ums_member(id bigint primary key)");
        jdbc.execute("create table club(id bigint primary key, member_id bigint not null, name varchar(80), "
                + "status bigint not null, reject_reason varchar(200), del_flag int not null, create_time timestamp)");
        jdbc.update("insert into ums_member(id) values (?)", OWNER);
        clubService = proxiedClubService();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
    }

    @Test
    void retryAfterALostReplyReplaysTheSameClubInsteadOfCreatingASecond() {
        Club first = request("周末徒步");
        clubService.insertClubWithinOwnerQuota(first, 2);
        Club retry = request("周末徒步");
        int count = clubService.insertClubWithinOwnerQuota(retry, 2);

        assertEquals(1, ownedClubCount());
        assertEquals(first.getId(), retry.getId());
        assertEquals(1, count);
    }

    @Test
    void twoConcurrentIdenticalCreatesCommitExactlyOneClub() throws Exception {
        Club a = request("夜跑团");
        Club b = request("夜跑团");
        CyclicBarrier ready = new CyclicBarrier(2);

        Future<Integer> first = workers.submit(() -> afterBarrier(ready, a));
        Future<Integer> second = workers.submit(() -> afterBarrier(ready, b));
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertEquals(1, ownedClubCount(), "两条并发的同一请求只能落一行");
        assertEquals(a.getId(), b.getId());
    }

    @Test
    void pendingApplicationIsReturnedInsteadOfInsertingAnother() {
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (12,?,'待审中',0,0)", OWNER);
        Club retry = request("待审中");

        clubService.insertClubWithinOwnerQuota(retry, 2);

        assertEquals(1, ownedClubCount());
        assertEquals(12L, retry.getId());
        assertEquals(0L, retry.getStatus());
    }

    @Test
    void twoConcurrentResubmitsAfterRejectionLeaveOnePendingRowWithoutTheOldReason() throws Exception {
        jdbc.update("insert into club(id,member_id,name,status,reject_reason,del_flag) "
                + "values (12,?,'旧名',2,'简介不完整',0)", OWNER);
        Club a = request("新名");
        Club b = request("新名");
        CyclicBarrier ready = new CyclicBarrier(2);

        Future<Integer> first = workers.submit(() -> afterBarrier(ready, a));
        Future<Integer> second = workers.submit(() -> afterBarrier(ready, b));
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);

        assertEquals(1, ownedClubCount());
        assertEquals(12L, a.getId());
        assertEquals(12L, b.getId());
        assertEquals(0L, jdbc.queryForObject("select status from club where id=12", Long.class));
        assertNull(jdbc.queryForObject("select reject_reason from club where id=12", String.class));
    }

    private Integer afterBarrier(CyclicBarrier ready, Club club) throws Exception {
        ready.await(5, TimeUnit.SECONDS);
        return clubService.insertClubWithinOwnerQuota(club, 2);
    }

    private IClubService proxiedClubService() {
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.lockMemberById(anyLong())).thenAnswer(call -> {
            List<Long> rows = jdbc.queryForList("select id from ums_member where id=? for update",
                    Long.class, call.getArgument(0, Long.class));
            return rows.isEmpty() ? null : rows.get(0);
        });
        UmsMember account = new UmsMember();
        account.setId(OWNER);
        account.setStatus(1L);
        account.setDelFlag(0);
        when(members.selectUmsMemberById(OWNER)).thenReturn(account);
        ClubLeader leader = new ClubLeader();
        leader.setId(700L);
        leader.setMemberId(OWNER);
        leader.setStatus(1);
        leader.setDelFlag(0);
        ClubLeaderMapper leaders = mock(ClubLeaderMapper.class);
        when(leaders.selectClubLeaderByMemberId(OWNER)).thenReturn(leader);

        ClubServiceImpl target = new ClubServiceImpl();
        ReflectionTestUtils.setField(target, "clubMapper", dataBackedClubs());
        ReflectionTestUtils.setField(target, "clubLeaderMapper", leaders);
        ReflectionTestUtils.setField(target, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(target, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberService", members);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubService.class);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (IClubService) factory.getProxy();
    }

    private ClubMapper dataBackedClubs() {
        ClubMapper mapper = mock(ClubMapper.class);
        when(mapper.selectOwnedClubIdsForUpdate(anyLong())).thenAnswer(call -> jdbc.queryForList(
                "select id from club where member_id=? and del_flag=0 order by id for update",
                Long.class, call.getArgument(0, Long.class)));
        when(mapper.selectClubsByIds(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            StringBuilder in = new StringBuilder();
            for (Long id : ids) in.append(in.length() == 0 ? "" : ",").append(id);
            return jdbc.query("select id,member_id,name,status,reject_reason,del_flag from club "
                    + "where del_flag=0 and id in (" + in + ")", (rs, n) -> {
                        Club row = new Club();
                        row.setId(rs.getLong("id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setName(rs.getString("name"));
                        row.setStatus(rs.getLong("status"));
                        row.setRejectReason(rs.getString("reject_reason"));
                        row.setDelFlag(rs.getInt("del_flag"));
                        return row;
                    });
        });
        when(mapper.insertClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            row.setId(clubIds.incrementAndGet());
            return jdbc.update("insert into club(id,member_id,name,status,del_flag,create_time) values (?,?,?,?,?,?)",
                    row.getId(), row.getMemberId(), row.getName(), row.getStatus(), row.getDelFlag(),
                    row.getCreateTime());
        });
        // 与 ClubMapper.xml updateClub 同口径:status 非驳回且未带原因时清空 reject_reason
        when(mapper.updateClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            boolean clear = row.getRejectReason() == null && row.getStatus() != null && row.getStatus() != 2L;
            return jdbc.update("update club set name=?, status=?"
                            + (clear ? ", reject_reason=null" : "") + " where id=?",
                    row.getName(), row.getStatus(), row.getId());
        });
        return mapper;
    }

    private int ownedClubCount() {
        return jdbc.queryForObject("select count(*) from club where member_id=? and del_flag=0",
                Integer.class, OWNER);
    }

    private Club request(String name) {
        Club club = new Club();
        club.setMemberId(OWNER);
        club.setName(name);
        club.setStatus(1L);
        club.setMemberCount(0);
        club.setDelFlag(0);
        return club;
    }
}
