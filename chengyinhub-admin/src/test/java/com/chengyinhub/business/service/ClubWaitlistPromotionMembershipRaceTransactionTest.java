package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 退会与候补晋升统一 club -> ticket 锁序；退会先持锁时，晋升等待并按提交后资格选下一位。 */
class ClubWaitlistPromotionMembershipRaceTransactionTest {
    private static final long CLUB = 7L;
    private static final long OWNER = 70L;
    private static final long LEAVER = 71L;
    private static final long NEXT = 72L;
    private static final long ACTIVITY = 31L;
    private static final long TICKET = 51L;
    private static final Date NOW = new Date(1_800_000_000_000L);

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final CountDownLatch exitHoldingClub = new CountDownLatch(1);
    private final CountDownLatch allowExitCommit = new CountDownLatch(1);
    private final CountDownLatch promotionWaitingForClub = new CountDownLatch(1);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IClubWaitlistService service;
    private boolean forceOfferCasFailure;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:waitlist_promotion_member_race;MODE=MySQL;DB_CLOSE_DELAY=-1;"
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
        jdbc.execute("create table oms_ticket(id bigint primary key, owner_id bigint not null, owner_type bigint not null, "
                + "remaining_inventory bigint not null, total_inventory bigint not null, del_flag int not null)");
        jdbc.execute("create table club_event_waitlist(id bigint primary key, club_id bigint not null, "
                + "activity_id bigint not null, ticket_id bigint not null, member_id bigint not null, "
                + "state varchar(16) not null, joined_at timestamp not null, offered_at timestamp null, "
                + "offer_expires_at timestamp null, offer_token_hash varchar(64) null, version int not null)");
        jdbc.update("insert into club(id,member_id,status,del_flag) values (?,?,1,0)", CLUB, OWNER);
        jdbc.update("insert into club_member(id,club_id,member_id,status,del_flag) values (1,?,?,1,0)", CLUB, LEAVER);
        jdbc.update("insert into club_member(id,club_id,member_id,status,del_flag) values (2,?,?,1,0)", CLUB, NEXT);
        jdbc.update("insert into oms_ticket(id,owner_id,owner_type,remaining_inventory,total_inventory,del_flag) "
                + "values (?,?,2,1,10,0)", TICKET, ACTIVITY);
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version) "
                + "values (61,?,?,?,?, 'WAITING',?,0)", CLUB, ACTIVITY, TICKET, LEAVER,
                new Timestamp(NOW.getTime() - 2_000L));
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,version) "
                + "values (62,?,?,?,?, 'WAITING',?,0)", CLUB, ACTIVITY, TICKET, NEXT,
                new Timestamp(NOW.getTime() - 1_000L));
        service = proxiedService(dataBackedMapper(), dataBackedTickets());
    }

    @AfterEach
    void tearDown() {
        allowExitCommit.countDown();
        workers.shutdownNow();
    }

    @Test
    void exitHoldingClubCommitsBeforePromotionRechecksAndNextMemberGetsSingleSlot() throws Exception {
        TransactionTemplate exit = new TransactionTemplate(transactions);
        Future<?> leave = workers.submit(() -> exit.execute(status -> {
            jdbc.queryForObject("select id from club where id=? for update", Long.class, CLUB);
            jdbc.update("delete from club_member where club_id=? and member_id=?", CLUB, LEAVER);
            exitHoldingClub.countDown();
            try {
                assertTrue(allowExitCommit.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return null;
        }));
        assertTrue(exitHoldingClub.await(5, TimeUnit.SECONDS));
        Future<Boolean> promotion = workers.submit(() -> service.promoteNext(ACTIVITY, TICKET, NOW));
        assertTrue(promotionWaitingForClub.await(5, TimeUnit.SECONDS));
        allowExitCommit.countDown();
        leave.get(5, TimeUnit.SECONDS);

        assertTrue(promotion.get(5, TimeUnit.SECONDS));
        assertEquals("CANCELLED", state(61L));
        assertEquals("OFFERED", state(62L));
        assertEquals(0L, jdbc.queryForObject(
                "select remaining_inventory from oms_ticket where id=?", Long.class, TICKET));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from club_event_waitlist where state='OFFERED'", Integer.class));
    }

    @Test
    void offerCasFailureRollsBackInventoryDeductionAndLeavesQueueWaiting() {
        jdbc.update("delete from club_event_waitlist where id=61");
        forceOfferCasFailure = true;

        assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> service.promoteNext(ACTIVITY, TICKET, NOW));

        assertEquals(1L, jdbc.queryForObject(
                "select remaining_inventory from oms_ticket where id=?", Long.class, TICKET));
        assertEquals("WAITING", state(62L));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from club_event_waitlist where state='OFFERED'", Integer.class));
    }

    private IClubWaitlistService proxiedService(ClubEventOpsMapper mapper, OmsTicketMapper tickets) {
        ClubWaitlistServiceImpl target = new ClubWaitlistServiceImpl(mapper, tickets,
                mock(IClubParticipationGuard.class), new ClubWaitlistTokenCodec("promotion-race-secret"));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(IClubWaitlistService.class);
        factory.addAdvice(new TransactionInterceptor(transactions,
                new AnnotationTransactionAttributeSource()));
        return (IClubWaitlistService) factory.getProxy();
    }

    private ClubEventOpsMapper dataBackedMapper() {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        when(mapper.selectSeriesPolicyByActivityForUpdate(ACTIVITY)).thenReturn(policy());
        when(mapper.selectFirstWaitingForUpdate(ACTIVITY, TICKET)).thenAnswer(call -> {
            List<ClubWaitlistEntry> rows = jdbc.query(
                    "select id,club_id,activity_id,ticket_id,member_id,state,joined_at,"
                            + "offered_at,offer_expires_at,offer_token_hash,version "
                            + "from club_event_waitlist where activity_id=? and ticket_id=? and state='WAITING' "
                            + "order by joined_at,id limit 1 for update",
                    (rs, rowNum) -> waitlist(rs), ACTIVITY, TICKET);
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(mapper.isActiveParticipant(CLUB, LEAVER)).thenAnswer(call -> activeParticipant(LEAVER));
        when(mapper.isActiveParticipant(CLUB, NEXT)).thenAnswer(call -> activeParticipant(NEXT));
        when(mapper.selectClubOwnerForUpdate(CLUB)).thenAnswer(call -> {
            promotionWaitingForClub.countDown();
            List<Long> rows = jdbc.query("select member_id from club where id=? and status=1 and del_flag=0 for update",
                    (rs, rowNum) -> rs.getLong(1), CLUB);
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(mapper.selectActiveClubMemberForUpdate(CLUB, LEAVER))
                .thenAnswer(call -> activeMemberForUpdate(LEAVER));
        when(mapper.selectActiveClubMemberForUpdate(CLUB, NEXT))
                .thenAnswer(call -> activeMemberForUpdate(NEXT));
        when(mapper.cancelActiveWaitlistCas(anyLong(), anyInt(),
                org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(call -> jdbc.update(
                        "update club_event_waitlist set state='CANCELLED',version=version+1 "
                                + "where id=? and version=? and state=? and member_id=? and activity_id=? and ticket_id=?",
                        call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(mapper.offerWaitingCas(anyLong(), anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Date.class),
                org.mockito.ArgumentMatchers.any(Date.class))).thenAnswer(call -> {
                    if (forceOfferCasFailure) return 0;
                    return jdbc.update("update club_event_waitlist set state='OFFERED',offer_token_hash=?,offered_at=?,"
                                    + "offer_expires_at=?,version=version+1 where id=? and version=? and state='WAITING'",
                            call.getArgument(2), call.getArgument(3), call.getArgument(4),
                            call.getArgument(0), call.getArgument(1));
                });
        return mapper;
    }

    private OmsTicketMapper dataBackedTickets() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        when(mapper.selectOmsTicketByIdForUpdate(TICKET)).thenAnswer(call -> jdbc.queryForObject(
                "select id,owner_id,owner_type,remaining_inventory,total_inventory,del_flag "
                        + "from oms_ticket where id=? for update",
                (rs, rowNum) -> {
                    OmsTicket row = new OmsTicket();
                    row.setId(rs.getLong("id"));
                    row.setOwnerId(rs.getLong("owner_id"));
                    row.setOwnerType(rs.getLong("owner_type"));
                    row.setRemainingInventory(rs.getLong("remaining_inventory"));
                    row.setTotalInventory(rs.getLong("total_inventory"));
                    row.setDelFlag(rs.getInt("del_flag"));
                    return row;
                }, TICKET));
        when(mapper.deductInventory(TICKET, 1L)).thenAnswer(call -> jdbc.update(
                "update oms_ticket set remaining_inventory=remaining_inventory-1 "
                        + "where id=? and remaining_inventory>=1", TICKET));
        return mapper;
    }

    private int activeParticipant(long memberId) {
        return jdbc.queryForObject("select case when exists (select 1 from club c where c.id=? "
                        + "and c.status=1 and c.del_flag=0 and (c.member_id=? or exists "
                        + "(select 1 from club_member cm where cm.club_id=c.id and cm.member_id=? "
                        + "and cm.status=1 and cm.del_flag=0))) then 1 else 0 end",
                Integer.class, CLUB, memberId, memberId);
    }

    private Long activeMemberForUpdate(long memberId) {
        List<Long> rows = jdbc.query("select member_id from club_member where club_id=? and member_id=? "
                        + "and status=1 and del_flag=0 for update",
                (rs, rowNum) -> rs.getLong(1), CLUB, memberId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClubWaitlistEntry waitlist(java.sql.ResultSet rs) throws java.sql.SQLException {
        ClubWaitlistEntry row = new ClubWaitlistEntry();
        row.setId(rs.getLong("id"));
        row.setClubId(rs.getLong("club_id"));
        row.setActivityId(rs.getLong("activity_id"));
        row.setTicketId(rs.getLong("ticket_id"));
        row.setMemberId(rs.getLong("member_id"));
        row.setState(rs.getString("state"));
        row.setJoinedAt(rs.getTimestamp("joined_at"));
        row.setOfferedAt(rs.getTimestamp("offered_at"));
        row.setOfferExpiresAt(rs.getTimestamp("offer_expires_at"));
        row.setOfferTokenHash(rs.getString("offer_token_hash"));
        row.setVersion(rs.getInt("version"));
        return row;
    }

    private ClubEventSeries policy() {
        ClubEventSeries policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(CLUB);
        policy.setWaitlistEnabled(true);
        policy.setOfferMinutes(20);
        policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        return policy;
    }

    private String state(long id) {
        return jdbc.queryForObject("select state from club_event_waitlist where id=?", String.class, id);
    }
}
