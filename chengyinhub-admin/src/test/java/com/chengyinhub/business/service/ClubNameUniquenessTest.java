package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户 2026-10-20 决定:俱乐部名称不能重复。待审或在营、未删除的俱乐部占用名称(去掉首尾空格后比较),
 * 被驳回和已解散的不占;本人重试仍按 SC-CL-CREATE 原样回放;改名才查重,名称没变(包括历史上已经重名的)照常保存。
 * 与 {@link ClubCreateRetryIdempotencyConcurrencyTest} 同法:真实 H2 事务,Mapper 只把生产窄查询映射到 JDBC。
 */
class ClubNameUniquenessTest {
    private static final long OWNER = 900L;
    private static final long OTHER = 901L;
    private static final String TAKEN = "俱乐部名称已被使用，请换一个";

    private final AtomicLong clubIds = new AtomicLong(100L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IClubService clubService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:club_name_unique;MODE=MySQL;DB_CLOSE_DELAY=-1;"
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

    @Test
    void anotherOwnersLiveClubKeepsItsName() {
        club(50, OTHER, "夜跑团", 1, 0);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2));

        assertEquals(TAKEN, refused.getMessage());
        assertEquals(0, ownedClubCount());
    }

    @Test
    void anotherOwnersPendingApplicationAlsoHoldsTheName() {
        club(51, OTHER, "夜跑团", 0, 0);

        assertThrows(ServiceException.class, () -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2));

        assertEquals(0, ownedClubCount());
    }

    @Test
    void surroundingSpacesDoNotMakeANewName() {
        club(52, OTHER, "夜跑团", 1, 0);

        assertThrows(ServiceException.class, () -> clubService.insertClubWithinOwnerQuota(request("  夜跑团 "), 2));

        assertEquals(0, ownedClubCount());
    }

    @Test
    void rejectedAndDissolvedClubsReleaseTheirNames() {
        club(53, OTHER, "夜跑团", 2, 0);
        club(54, OTHER, "晨跑团", 1, 2);

        clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2);
        clubService.insertClubWithinOwnerQuota(request("晨跑团"), 2);

        assertEquals(2, ownedClubCount());
    }

    @Test
    void theSameOwnersRetryIsStillReplayedInsteadOfRefused() {
        Club first = request("夜跑团");
        clubService.insertClubWithinOwnerQuota(first, 2);
        Club retry = request("夜跑团");

        clubService.insertClubWithinOwnerQuota(retry, 2);

        assertEquals(1, ownedClubCount());
        assertEquals(first.getId(), retry.getId());
    }

    @Test
    void resubmittingARejectedApplicationUnderATakenNameIsRefusedAndLeavesTheRowAlone() {
        jdbc.update("insert into club(id,member_id,name,status,reject_reason,del_flag) values (12,?,'旧名',2,'简介不完整',0)",
                OWNER);
        club(55, OTHER, "夜跑团", 1, 0);

        assertThrows(ServiceException.class, () -> clubService.insertClubWithinOwnerQuota(request("夜跑团"), 2));

        assertEquals(2L, jdbc.queryForObject("select status from club where id=12", Long.class));
        assertEquals("旧名", jdbc.queryForObject("select name from club where id=12", String.class));
    }

    @Test
    void renamingToANameAnotherClubHoldsIsRefused() {
        club(12, OWNER, "旧名", 1, 0);
        club(56, OTHER, "夜跑团", 1, 0);
        Club rename = new Club();
        rename.setId(12L);
        rename.setName("夜跑团");

        ServiceException refused = assertThrows(ServiceException.class, () -> clubService.updateClub(rename));

        assertEquals(TAKEN, refused.getMessage());
        assertEquals("旧名", jdbc.queryForObject("select name from club where id=12", String.class));
    }

    @Test
    void anEditThatKeepsTheNameSavesEvenWhereOldDataAlreadyRepeatsIt() {
        club(12, OWNER, "老名字", 1, 0);
        club(57, OTHER, "老名字", 1, 0);
        Club edit = new Club();
        edit.setId(12L);
        edit.setName("老名字");

        assertEquals(1, clubService.updateClub(edit));
    }

    @Test
    void theBackOfficeCannotAddAClubUnderATakenName() {
        club(58, OTHER, "夜跑团", 1, 0);
        Club added = request("夜跑团");

        assertThrows(ServiceException.class, () -> clubService.insertClub(added));

        assertEquals(1, jdbc.queryForObject("select count(*) from club where name='夜跑团'", Integer.class));
    }

    private void club(long id, long memberId, String name, long status, int delFlag) {
        jdbc.update("insert into club(id,member_id,name,status,del_flag) values (?,?,?,?,?)",
                id, memberId, name, status, delFlag);
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
        when(mapper.selectClubById(anyLong())).thenAnswer(call -> {
            List<Club> rows = jdbc.query("select id,member_id,name,status from club where id=? and del_flag=0",
                    (rs, n) -> {
                        Club row = new Club();
                        row.setId(rs.getLong("id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setName(rs.getString("name"));
                        row.setStatus(rs.getLong("status"));
                        return row;
                    }, call.getArgument(0, Long.class));
            return rows.isEmpty() ? null : rows.get(0);
        });
        // 与 ClubMapper.xml countClubsHoldingName 同口径
        when(mapper.countClubsHoldingName(anyString(), any(), any())).thenAnswer(call -> {
            StringBuilder sql = new StringBuilder(
                    "select count(*) from club where del_flag=0 and status in (0,1) and trim(name)=?");
            List<Object> args = new ArrayList<>();
            args.add(call.getArgument(0, String.class));
            Long member = call.getArgument(1);
            Long exclude = call.getArgument(2);
            if (member != null) { sql.append(" and member_id<>?"); args.add(member); }
            if (exclude != null) { sql.append(" and id<>?"); args.add(exclude); }
            return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        });
        when(mapper.insertClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            row.setId(clubIds.incrementAndGet());
            return jdbc.update("insert into club(id,member_id,name,status,del_flag,create_time) values (?,?,?,?,?,?)",
                    row.getId(), row.getMemberId(), row.getName(), row.getStatus(), row.getDelFlag(),
                    row.getCreateTime());
        });
        when(mapper.updateClub(any(Club.class))).thenAnswer(call -> {
            Club row = call.getArgument(0, Club.class);
            return row.getName() == null ? 1
                    : jdbc.update("update club set name=? where id=?", row.getName(), row.getId());
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
