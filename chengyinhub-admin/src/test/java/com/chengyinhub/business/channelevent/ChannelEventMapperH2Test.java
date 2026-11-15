package com.chengyinhub.business.channelevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Statement;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.channelevent.mapper.ChannelEventMapper;

/**
 * Runs the channel event SQL on H2 (MySQL mode): going = confirmed enrolments only, RSVP upsert, maybe/no counts that
 * exclude enrolled members, list ordering, photos, the card message and the unread fan-out.
 * Not a substitute for MySQL 8 (see the receipt), but it catches column and predicate mistakes.
 */
class ChannelEventMapperH2Test
{
    static final long HOUR = 3600_000L;
    SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(false);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:chev_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ChannelEventMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement st = session.getConnection().createStatement())
        {
            st.execute("create table channel_event (id bigint auto_increment primary key, club_id bigint not null, channel_id bigint not null,"
                    + " activity_id bigint not null unique, ticket_id bigint, message_id bigint, poster_member_id bigint not null,"
                    + " title varchar(60) not null, start_at timestamp not null, end_at timestamp not null, place_name varchar(80),"
                    + " lat decimal(10,7), lng decimal(10,7), capacity int not null default 0, paid tinyint not null default 0,"
                    + " status tinyint not null default 0, create_time timestamp, update_time timestamp)");
            st.execute("create table channel_event_rsvp (id bigint auto_increment primary key, event_id bigint not null, member_id bigint not null,"
                    + " status varchar(8) not null, create_time timestamp, update_time timestamp, unique (event_id, member_id))");
            st.execute("create table channel_event_photo (id bigint auto_increment primary key, event_id bigint not null, member_id bigint not null,"
                    + " asset_id bigint not null, removed tinyint not null default 0, removed_by bigint, create_time timestamp,"
                    + " unique (event_id, asset_id))");
            st.execute("create table cms_registration (id bigint auto_increment primary key, owner_type int, owner_id bigint, member_id bigint,"
                    + " payment_status int, registration_status int)");
            st.execute("create table ums_member (id bigint primary key, nickname varchar(64), avatar varchar(255), del_flag int default 0)");
            st.execute("create table cms_activity (id bigint primary key, status int, publish_status int, del_flag int, cancel_time timestamp,"
                    + " club_id bigint, member_id bigint)");
            st.execute("create table oms_ticket (id bigint primary key, owner_type int, owner_id bigint, del_flag int, remaining_inventory int)");
            st.execute("create table im_channel (id bigint primary key, status int, legacy_conversation_id bigint)");
            st.execute("create table im_conversation (id bigint primary key, type int, last_msg_type int, last_msg_text varchar(255),"
                    + " last_msg_at timestamp, update_time timestamp)");
            st.execute("create table im_conversation_member (conversation_id bigint, member_id bigint, deleted int, unread_count int)");
            st.execute("create table im_message (id bigint auto_increment primary key, conversation_id bigint, sender_id bigint, receiver_id bigint,"
                    + " msg_type int, content varchar(2000), extra_json varchar(1000), status int, check_status int, client_message_id varchar(64),"
                    + " create_time timestamp, visible_at timestamp(6))");
            st.execute("insert into ums_member values (1,'host','https://a/1',0),(2,'ann',null,0),(3,'bo',null,0),(4,'cy',null,0)");
            st.execute("insert into cms_activity values (900,1,1,0,null,42,1)");
            st.execute("insert into oms_ticket values (300,2,900,0,8),(301,2,900,1,0)");
            // Member 2 enrolled and confirmed; member 3 waiting for payment; member 4 refunded.
            st.execute("insert into cms_registration (owner_type,owner_id,member_id,payment_status,registration_status) values"
                    + " (2,900,2,2,2),(2,900,3,0,1),(2,900,4,4,3),(2,901,3,2,2)");
            st.execute("insert into im_channel values (50,0,null)");
            st.execute("insert into im_conversation (id,type) values (50,5)");
            st.execute("insert into im_conversation_member values (50,1,0,0),(50,2,0,0),(50,3,1,0)");
        }
    }

    /** H2 lowercases column aliases; MySQL keeps them. Read map rows without caring about case. */
    static Object get(Map<String, Object> row, String key)
    {
        for (Map.Entry<String, Object> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    static Map<String, Object> camel(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "status", "publishStatus", "delFlag", "cancelTime", "clubId", "memberId")) out.put(k, get(row, k));
        return out;
    }

    ChannelEventRow event(long activityId, long startOffset, long endOffset)
    {
        long now = System.currentTimeMillis();
        ChannelEventRow r = new ChannelEventRow();
        r.setClubId(42L);
        r.setChannelId(50L);
        r.setActivityId(activityId);
        r.setTicketId(300L);
        r.setPosterMemberId(1L);
        r.setTitle("夜跑 " + activityId);
        r.setStartAt(new Date(now + startOffset));
        r.setEndAt(new Date(now + endOffset));
        r.setPlaceName("滨江");
        r.setLat(new BigDecimal("31.2304000"));
        r.setLng(new BigDecimal("121.4737000"));
        r.setCapacity(10);
        r.setPaid(0);
        r.setStatus(ChannelEventRow.STATUS_POSTED);
        return r;
    }

    @Test
    void goingCountsOnlyConfirmedEnrolmentsAndRsvpCountsSkipEnrolledMembers()
    {
        try (SqlSession s = factory.openSession(true))
        {
            ChannelEventMapper m = s.getMapper(ChannelEventMapper.class);
            ChannelEventRow e = event(900, 24 * HOUR, 26 * HOUR);
            assertEquals(1, m.insertEvent(e));
            assertNotNull(e.getId());
            assertEquals(300L, m.selectFirstTicketId(900));
            assertEquals(8, m.selectRemaining(300));
            assertEquals(1, m.countGoing(900));
            assertEquals(1, m.countMyConfirmed(900, 2));
            assertEquals(0, m.countMyConfirmed(900, 3), "waiting for payment is not going");
            Map<String, Object> open = m.selectMyOpenRegistration(900, 3);
            assertEquals(1, ((Number) get(open, "registrationStatus")).intValue());
            assertNull(m.selectMyOpenRegistration(900, 4), "a cancelled enrolment is not open");

            m.upsertRsvp(e.getId(), 2, "NO");
            m.upsertRsvp(e.getId(), 3, "MAYBE");
            m.upsertRsvp(e.getId(), 4, "NO");
            m.upsertRsvp(e.getId(), 4, "MAYBE");
            assertEquals("MAYBE", m.selectMyRsvp(e.getId(), 4), "upsert replaces the answer");
            assertEquals(2, m.countRsvpWithoutEnrolment(e.getId(), 900, "MAYBE"));
            assertEquals(0, m.countRsvpWithoutEnrolment(e.getId(), 900, "NO"), "an enrolled member's no is still a held place");
            List<Map<String, Object>> going = m.selectGoingMembers(900, 50);
            assertEquals(1, going.size());
            assertEquals("ann", get(going.get(0), "nickname"));
            assertEquals(2, m.selectMaybeMembers(e.getId(), 900, 50).size());
            assertEquals(List.of(3L, 4L), m.selectMaybeMemberIdsByTicket(300).stream().sorted().toList());
        }
    }

    @Test
    void listPutsRunningAndUpcomingFirstAndHidesCancelled()
    {
        try (SqlSession s = factory.openSession(true))
        {
            ChannelEventMapper m = s.getMapper(ChannelEventMapper.class);
            m.insertEvent(event(1, 48 * HOUR, 50 * HOUR));
            m.insertEvent(event(2, -48 * HOUR, -46 * HOUR));
            m.insertEvent(event(3, 2 * HOUR, 3 * HOUR));
            ChannelEventRow cancelled = event(4, 5 * HOUR, 6 * HOUR);
            m.insertEvent(cancelled);
            m.markCancelled(cancelled.getId());
            List<ChannelEventRow> rows = m.selectByChannel(50, new Date(), 10);
            assertEquals(List.of(3L, 1L, 2L), rows.stream().map(ChannelEventRow::getActivityId).toList());
            assertEquals(4, m.selectForAdmin(42L, null, 10).size());
            assertEquals(0, m.selectForAdmin(43L, null, 10).size());
        }
    }

    @Test
    void cardMessageGoesIntoTheChannelConversationAndBumpsOtherMembersUnread()
    {
        try (SqlSession s = factory.openSession(true))
        {
            ChannelEventMapper m = s.getMapper(ChannelEventMapper.class);
            ChannelEventRow e = event(900, 24 * HOUR, 26 * HOUR);
            e.setStatus(ChannelEventRow.STATUS_WAITING_REVIEW);
            m.insertEvent(e);
            assertEquals(1, m.selectWaitingByChannel(50).size());
            Map<String, Object> state = m.selectActivityState(900);
            assertTrue(ChannelEventService.activityVisible(camel(state)));
            Map<String, Object> bridge = m.selectChannelConversation(50);
            assertEquals(50L, ((Number) get(bridge, "conversationId")).longValue());
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("conversationId", 50L);
            msg.put("senderId", 1L);
            msg.put("content", "【活动】夜跑");
            msg.put("extraJson", "{\"cardType\":\"channel_event\"}");
            msg.put("clientMessageId", "chev-1");
            assertEquals(1, m.insertCardMessage(msg));
            long messageId = ((Number) msg.get("id")).longValue();
            assertEquals(1, m.markPosted(e.getId(), messageId));
            assertEquals(0, m.markPosted(e.getId(), messageId), "a card is posted once");
            assertEquals(messageId, m.selectEvent(e.getId()).getMessageId());
            assertEquals(1, m.touchConversation(50, "【活动】夜跑"));
            assertEquals(1, m.fanOutUnread(50, 1), "only member 2: not the sender, not a member who left");
        }
    }

    @Test
    void photosKeepRemovedOnesForTheConsoleOnly()
    {
        try (SqlSession s = factory.openSession(true))
        {
            ChannelEventMapper m = s.getMapper(ChannelEventMapper.class);
            Map<String, Object> p1 = new LinkedHashMap<>(Map.of("eventId", 7L, "memberId", 2L, "assetId", 11L));
            Map<String, Object> p2 = new LinkedHashMap<>(Map.of("eventId", 7L, "memberId", 2L, "assetId", 12L));
            m.insertPhoto(p1);
            m.insertPhoto(p2);
            long id1 = ((Number) p1.get("id")).longValue();
            assertEquals(2, m.countMyPhotos(7, 2));
            m.setPhotoRemoved(id1, 1, 1L);
            assertEquals(1, m.selectPhotos(7, false, null, 30).size());
            assertEquals(2, m.selectPhotos(7, true, null, 30).size());
            assertEquals(1, m.countMyPhotos(7, 2));
            assertEquals(1L, ((Number) get(m.selectPhoto(id1), "removedBy")).longValue());
        }
    }
}
