package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubWaitlistClaim;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 候补认领与退出并发时，建单事务必须在 club 行锁后重验同俱乐部的有效成员关系。 */
class ClubWaitlistClaimMembershipRaceTransactionTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 70L;
    private static final long MEMBER = 71L;
    private static final long ACTIVITY = 31L;
    private static final long TICKET = 51L;
    private static final long OFFER = 61L;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final CountDownLatch offerClaimed = new CountDownLatch(1);
    private final CountDownLatch exitCommitted = new CountDownLatch(1);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IRegistrationCreationTransaction service;
    private OmsTicketMapper tickets;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:waitlist_claim_member_race;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table club(id bigint primary key, member_id bigint not null, status bigint not null, "
                + "del_flag int not null)");
        jdbc.execute("create table club_member(id bigint primary key, club_id bigint not null, member_id bigint not null, "
                + "status int not null, del_flag int not null)");
        jdbc.execute("create table waitlist_offer(id bigint primary key, state varchar(16) not null)");
        jdbc.update("insert into club(id,member_id,status,del_flag) values (?,?,1,0)", CLUB, OWNER);
        jdbc.update("insert into club_member(id,club_id,member_id,status,del_flag) values (1,?,?,1,0)", CLUB, MEMBER);
        jdbc.update("insert into waitlist_offer(id,state) values (?,'OFFERED')", OFFER);
        tickets = mock(OmsTicketMapper.class);
        service = proxiedService();
    }

    @AfterEach
    void tearDown() {
        exitCommitted.countDown();
        workers.shutdownNow();
    }

    @Test
    void memberExitBetweenClaimAndClubLockRejectsOrderAndRollsBackClaimedCas() throws Exception {
        Future<?> create = workers.submit(() -> service.create(MEMBER, request(), "waitlist-race", null));
        assertTrue(offerClaimed.await(5, TimeUnit.SECONDS));
        assertEquals("OFFERED", jdbc.queryForObject(
                "select state from waitlist_offer where id=?", String.class, OFFER),
                "CLAIMED CAS 必须仍在 create 的 REQUIRED 事务内，不能提前提交");

        TransactionTemplate exit = new TransactionTemplate(transactions);
        Future<?> leave = workers.submit(() -> exit.execute(status -> {
            jdbc.queryForObject("select id from club where id=? for update", Long.class, CLUB);
            jdbc.update("delete from club_member where club_id=? and member_id=?", CLUB, MEMBER);
            return null;
        }));
        leave.get(5, TimeUnit.SECONDS);
        exitCommitted.countDown();

        try {
            create.get(5, TimeUnit.SECONDS);
            fail("退会已提交后，候补报名不得继续建单");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("仅俱乐部正常成员可认领候补名额"),
                    expected.getCause().toString());
        }

        assertEquals("OFFERED", jdbc.queryForObject(
                "select state from waitlist_offer where id=?", String.class, OFFER));
        assertEquals(0, jdbc.queryForObject("select count(*) from club_member", Integer.class));
        verify(tickets, never()).selectOmsTicketById(any(Long.class));
    }

    @Test
    void clubOwnerRemainsEligibleWithoutClubMemberRow() {
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ClubMemberMapper members = mock(ClubMemberMapper.class);
        ReflectionTestUtils.setField(target, "clubMemberMapper", members);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(target,
                "revalidateWaitlistParticipant", claim(OWNER, CLUB), OWNER,
                java.util.Collections.singletonList(club(CLUB, OWNER))));

        verify(members, never()).selectByClubAndMemberForUpdate(any(Long.class), any(Long.class));
    }

    @Test
    void activeMemberIsRecheckedWithForUpdateOnClaimClub() {
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ClubMemberMapper members = mock(ClubMemberMapper.class);
        ClubMember active = new ClubMember();
        active.setClubId(CLUB);
        active.setMemberId(MEMBER);
        active.setStatus(1);
        active.setDelFlag(0);
        when(members.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenReturn(active);
        ReflectionTestUtils.setField(target, "clubMemberMapper", members);

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(target,
                "revalidateWaitlistParticipant", claim(MEMBER, CLUB), MEMBER,
                java.util.Collections.singletonList(club(CLUB, OWNER))));

        verify(members).selectByClubAndMemberForUpdate(CLUB, MEMBER);
    }

    @Test
    void claimClubMustBeOneOfTheLockedOwningClubs() {
        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "clubMemberMapper", mock(ClubMemberMapper.class));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(target,
                        "revalidateWaitlistParticipant", claim(MEMBER, 8L), MEMBER,
                        java.util.Collections.singletonList(club(CLUB, OWNER))));

        assertTrue(error.getMessage().contains("候补俱乐部范围不一致"));
    }

    private IRegistrationCreationTransaction proxiedService() {
        CmsActivityMapper activities = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setClubId(CLUB);
        activity.setName("候补场次");
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activities.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activities.selectCmsActivityByIdForUpdate(ACTIVITY)).thenReturn(activity);

        IClubWaitlistOrderBridge bridge = mock(IClubWaitlistOrderBridge.class);
        when(bridge.claimForOrder(eq(MEMBER), any(RegistrationRequest.class), any(Date.class)))
                .thenAnswer(call -> {
                    assertEquals(1, jdbc.update(
                            "update waitlist_offer set state='CLAIMED' where id=? and state='OFFERED'", OFFER));
                    return new ClubWaitlistClaim(OFFER, CLUB, MEMBER, ACTIVITY, TICKET,
                            new Date(System.currentTimeMillis() + 60_000L));
                });

        CmsRegistrationServiceImpl target = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(target, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(target, "clubMapper", dataBackedClubMapper());
        ReflectionTestUtils.setField(target, "clubMemberMapper", dataBackedMemberMapper());
        ReflectionTestUtils.setField(target, "omsTicketMapper", tickets);
        ReflectionTestUtils.setField(target, "clubWaitlistOrderBridge", bridge);

        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IRegistrationCreationTransaction.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (IRegistrationCreationTransaction) factory.getProxy();
    }

    private ClubMapper dataBackedClubMapper() {
        ClubMapper mapper = mock(ClubMapper.class);
        when(mapper.selectClubByIdForUpdate(CLUB)).thenAnswer(call -> jdbc.queryForObject(
                "select id,member_id,status,del_flag from club where id=? and del_flag=0 for update",
                (rs, rowNum) -> {
                    Club row = new Club();
                    row.setId(rs.getLong("id"));
                    row.setMemberId(rs.getLong("member_id"));
                    row.setStatus(rs.getLong("status"));
                    row.setDelFlag(rs.getInt("del_flag"));
                    return row;
                }, CLUB));
        return mapper;
    }

    private ClubMemberMapper dataBackedMemberMapper() {
        ClubMemberMapper mapper = mock(ClubMemberMapper.class);
        when(mapper.selectByClubAndMember(CLUB, MEMBER)).thenAnswer(call -> {
            assertEquals(1, jdbc.queryForObject("select count(*) from club_member "
                    + "where club_id=? and member_id=? and status=1 and del_flag=0", Integer.class, CLUB, MEMBER));
            ClubMember active = new ClubMember();
            active.setClubId(CLUB);
            active.setMemberId(MEMBER);
            active.setStatus(1);
            active.setDelFlag(0);
            // 退出发生在普通资格读取之后，确保仍覆盖行锁后的重验和事务回滚。
            offerClaimed.countDown();
            assertTrue(exitCommitted.await(5, TimeUnit.SECONDS));
            return active;
        });
        when(mapper.selectByClubAndMemberForUpdate(CLUB, MEMBER)).thenAnswer(call -> {
            List<ClubMember> rows = jdbc.query(
                    "select id,club_id,member_id,status,del_flag from club_member "
                            + "where club_id=? and member_id=? and status=1 and del_flag=0 for update",
                    (rs, rowNum) -> {
                        ClubMember row = new ClubMember();
                        row.setId(rs.getLong("id"));
                        row.setClubId(rs.getLong("club_id"));
                        row.setMemberId(rs.getLong("member_id"));
                        row.setStatus(rs.getInt("status"));
                        row.setDelFlag(rs.getInt("del_flag"));
                        return row;
                    }, CLUB, MEMBER);
            return rows.isEmpty() ? null : rows.get(0);
        });
        return mapper;
    }

    private RegistrationRequest request() {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(ACTIVITY);
        request.setTicketId(TICKET);
        request.setIsUsePoint(0);
        request.setRealName("张三");
        request.setPhone("18800000000");
        request.setWaitlistOfferId(OFFER);
        request.setWaitlistOfferToken("opaque-token");
        return request;
    }

    private ClubWaitlistClaim claim(long memberId, long clubId) {
        return new ClubWaitlistClaim(OFFER, clubId, memberId, ACTIVITY, TICKET,
                new Date(System.currentTimeMillis() + 60_000L));
    }

    private Club club(long clubId, long ownerId) {
        Club club = new Club();
        club.setId(clubId);
        club.setMemberId(ownerId);
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }
}
