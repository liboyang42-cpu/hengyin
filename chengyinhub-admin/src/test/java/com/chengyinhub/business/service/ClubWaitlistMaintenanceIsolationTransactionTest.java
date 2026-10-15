package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.ClubWaitlistServiceImpl;
import com.chengyinhub.business.service.impl.ClubWaitlistTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 坏候补行必须是可重复观察的 poison pill，不能回滚同批后续好行。 */
class ClubWaitlistMaintenanceIsolationTransactionTest {
    private static final long CLUB = 7L;
    private static final long BAD_ACTIVITY = 31L;
    private static final long GOOD_ACTIVITY = 32L;
    private static final long BAD_TICKET = 51L;
    private static final long GOOD_TICKET = 52L;
    private static final Date NOW = new Date(1_800_000_000_000L);

    private JdbcTemplate jdbc;
    private ClubWaitlistServiceImpl service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:waitlist_maintenance_isolation;MODE=MySQL;DB_CLOSE_DELAY=-1;"
                + "DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop all objects");
        jdbc.execute("create table oms_ticket(id bigint primary key, owner_id bigint not null, owner_type bigint not null, "
                + "remaining_inventory bigint not null, total_inventory bigint not null, del_flag int not null)");
        jdbc.execute("create table club_event_waitlist(id bigint primary key, club_id bigint not null, "
                + "activity_id bigint not null, ticket_id bigint not null, member_id bigint not null, "
                + "state varchar(16) not null, joined_at timestamp not null, offered_at timestamp null, "
                + "offer_expires_at timestamp null, offer_token_hash varchar(64) null, version int not null)");
        jdbc.update("insert into oms_ticket(id,owner_id,owner_type,remaining_inventory,total_inventory,del_flag) "
                + "values (?,?,2,0,10,0)", GOOD_TICKET, GOOD_ACTIVITY);
        insertOffered(61L, BAD_ACTIVITY, BAD_TICKET);
        insertOffered(62L, GOOD_ACTIVITY, GOOD_TICKET);

        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        service = new ClubWaitlistServiceImpl(dataBackedMapper(), dataBackedTickets(),
                mock(IClubParticipationGuard.class), new ClubWaitlistTokenCodec("maintenance-secret"),
                transactions);
    }

    @Test
    void missingTicketPoisonDoesNotBlockGoodExpiryAndRemainsRetryable() {
        assertEquals(1, service.maintain(NOW));

        assertEquals("OFFERED", state(61L));
        assertEquals("EXPIRED", state(62L));
        assertEquals(1L, jdbc.queryForObject(
                "select remaining_inventory from oms_ticket where id=?", Long.class, GOOD_TICKET));

        assertEquals(0, service.maintain(NOW));
        assertEquals("OFFERED", state(61L));
        assertEquals(1L, jdbc.queryForObject(
                "select remaining_inventory from oms_ticket where id=?", Long.class, GOOD_TICKET));
    }

    private ClubEventOpsMapper dataBackedMapper() {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        when(mapper.selectWaitlistMaintenanceMaxId()).thenAnswer(call ->
                jdbc.queryForObject("select max(id) from club_event_waitlist", Long.class));
        when(mapper.selectWaitlistMaintenancePage(eq(0), any(Date.class), anyLong(), anyLong(), anyInt()))
                .thenAnswer(call -> jdbc.queryForList("select id as scanId, id as targetId from club_event_waitlist "
                        + "where state='OFFERED' and offer_expires_at<=? and id>? and id<=? order by id limit ?",
                        new Object[] {call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)}).stream().map(row -> {
                            java.util.Map<String, Object> normalized = new java.util.HashMap<>();
                            normalized.put("scanId", row.get("SCANID")); normalized.put("targetId", row.get("TARGETID"));
                            return normalized;
                        }).collect(java.util.stream.Collectors.toList()));
        when(mapper.selectSeriesByActivityAnyStatusForUpdate(any(Long.class))).thenAnswer(call -> policy());
        when(mapper.selectSeriesPolicyByActivity(any(Long.class))).thenReturn(null);
        when(mapper.selectWaitlistById(any(Long.class))).thenAnswer(call -> selectWaitlist(call.getArgument(0), false));
        when(mapper.selectWaitlistByIdForUpdate(any(Long.class)))
                .thenAnswer(call -> selectWaitlist(call.getArgument(0), true));
        when(mapper.expireOfferedCas(any(Long.class), anyInt(), any(Date.class))).thenAnswer(call -> jdbc.update(
                "update club_event_waitlist set state='EXPIRED',version=version+1 "
                        + "where id=? and state='OFFERED' and version=? and offer_expires_at<=?",
                call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        return mapper;
    }

    private OmsTicketMapper dataBackedTickets() {
        OmsTicketMapper mapper = mock(OmsTicketMapper.class);
        when(mapper.selectOmsTicketByIdForUpdate(any(Long.class))).thenAnswer(call -> {
            List<OmsTicket> rows = jdbc.query(
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
                    }, (Object) call.getArgument(0, Long.class));
            return rows.isEmpty() ? null : rows.get(0);
        });
        when(mapper.restoreInventory(GOOD_TICKET, 1L)).thenAnswer(call -> jdbc.update(
                "update oms_ticket set remaining_inventory=remaining_inventory+1 where id=?", GOOD_TICKET));
        return mapper;
    }

    private ClubWaitlistEntry selectWaitlist(long id, boolean forUpdate) {
        List<ClubWaitlistEntry> rows = jdbc.query(
                "select id,club_id,activity_id,ticket_id,member_id,state,joined_at,offered_at,"
                        + "offer_expires_at,offer_token_hash,version from club_event_waitlist where id=?"
                        + (forUpdate ? " for update" : ""),
                (rs, rowNum) -> {
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
                }, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ClubEventSeries policy() {
        ClubEventSeries policy = new ClubEventSeries();
        policy.setId(11L);
        policy.setClubId(CLUB);
        policy.setStatus(ClubEventSeries.STATUS_ACTIVE);
        policy.setWaitlistEnabled(true);
        policy.setOfferMinutes(20);
        return policy;
    }

    private void insertOffered(long id, long activityId, long ticketId) {
        jdbc.update("insert into club_event_waitlist(id,club_id,activity_id,ticket_id,member_id,state,joined_at,"
                        + "offered_at,offer_expires_at,offer_token_hash,version) "
                        + "values (?,?,?,?,?,'OFFERED',?,?,?,'hash',1)",
                id, CLUB, activityId, ticketId, 100L + id,
                new Timestamp(NOW.getTime() - 10_000L), new Timestamp(NOW.getTime() - 5_000L),
                new Timestamp(NOW.getTime() - 1L));
    }

    private String state(long id) {
        return jdbc.queryForObject("select state from club_event_waitlist where id=?", String.class, id);
    }
}
