package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.impl.ClubEventNotificationTransactionService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 取消通知按事务内 delivery 快照投递；普通通知仍必须按当前候补状态复核。 */
class ClubCancellationSnapshotEligibilityMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_cancel_snapshot_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table club (id bigint primary key, member_id bigint, status int, del_flag int)");
            statement.execute("create table club_member (club_id bigint, member_id bigint, status int, del_flag int)");
            statement.execute("create table club_governance_ban (club_id bigint, target_member_id bigint, "
                    + "status varchar(16), source_type varchar(16), expires_at timestamp)");
            statement.execute("create table cms_activity (id bigint primary key, club_id bigint, del_flag int, "
                    + "cancel_time timestamp, publish_status bigint)");
            statement.execute("create table club_event_series (id bigint primary key, club_id bigint)");
            statement.execute("create table club_event_occurrence (series_id bigint, activity_id bigint, status varchar(16))");
            statement.execute("create table club_event_waitlist (club_id bigint, activity_id bigint, "
                    + "member_id bigint, state varchar(16))");
            statement.execute("create table club_event_notification_campaign (id bigint primary key, club_id bigint, "
                    + "activity_id bigint, audience_type varchar(24), request_id varchar(64))");
            statement.execute("create table club_event_notification_delivery (id bigint primary key, "
                    + "campaign_id bigint, recipient_member_id bigint, channel varchar(16), status varchar(32), "
                    + "attempt_count int, message_id bigint, last_error varchar(500), sent_at timestamp, "
                    + "version int, create_time timestamp, update_time timestamp)");
            statement.execute("create table club_event_notification_outbox (delivery_id bigint primary key, "
                    + "status varchar(16), next_attempt_at timestamp, create_time timestamp, update_time timestamp)");

            statement.execute("insert into club values (21,42,1,0)");
            statement.execute("insert into club_member values (21,201,1,0)");
            statement.execute("insert into cms_activity values (71,21,0,current_timestamp,0)");
            statement.execute("insert into club_event_series values (61,21)");
            statement.execute("insert into club_event_occurrence values (61,71,'CANCELLED')");
            statement.execute("insert into club_event_waitlist values (21,71,201,'CANCELLED')");
            statement.execute("insert into club_event_notification_campaign values "
                    + "(101,21,71,'WAITLIST','cancel-w-snapshot'),"
                    + "(102,21,71,'WAITLIST','ordinary-waitlist')");
            statement.execute("insert into club_event_notification_delivery "
                    + "(id,campaign_id,recipient_member_id,channel,status,attempt_count,version,create_time,update_time) values "
                    + "(1001,101,201,'IN_APP','PENDING',0,0,current_timestamp,current_timestamp),"
                    + "(1002,102,201,'IN_APP','PENDING',0,0,current_timestamp,current_timestamp)");
            statement.execute("insert into club_event_notification_outbox "
                    + "values (1001,'PENDING',current_timestamp,current_timestamp,current_timestamp),"
                    + "(1002,'PENDING',current_timestamp,current_timestamp,current_timestamp)");
        }
    }

    @Test
    void cancellationSnapshotRemainsEligibleAfterWaitlistTerminationButOrdinaryCampaignDoesNot() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);

            assertEquals(1, mapper.isEligibleNotificationRecipient(
                    101L, 21L, 71L, "WAITLIST", 201L));
            assertEquals(0, mapper.isEligibleNotificationRecipient(
                    102L, 21L, 71L, "WAITLIST", 201L));
            assertEquals(0, mapper.isEligibleNotificationRecipient(
                    101L, 21L, 72L, "WAITLIST", 201L));
        }
    }

    @Test
    void cancellationSnapshotStillFailsClosedForCurrentClubBan() {
        try (SqlSession session = factory.openSession(true)) {
            session.getConnection().createStatement().execute(
                    "insert into club_governance_ban values (21,201,'ACTIVE','CLUB',null)");

            assertEquals(0, session.getMapper(ClubEventOpsMapper.class)
                    .isEligibleNotificationRecipient(101L, 21L, 71L, "WAITLIST", 201L));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void realDeliverySendsCancellationSnapshotButTerminallySkipsOrdinaryWaitlistCampaign() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);
            IImService imService = mock(IImService.class);
            when(imService.sendSystem(eq(201L), eq(1), any(String.class), any(String.class)))
                    .thenReturn(9001L);
            ClubEventNotificationTransactionService deliveryService =
                    new ClubEventNotificationTransactionService(mapper, imService);

            assertTrue(deliveryService.deliver(campaign(101L, "cancel-w-snapshot"), 1001L));
            assertFalse(deliveryService.deliver(campaign(102L, "ordinary-waitlist"), 1002L));

            assertEquals("SENT", scalar(session,
                    "select status from club_event_notification_delivery where id=1001"));
            assertEquals("SKIPPED_INELIGIBLE", scalar(session,
                    "select status from club_event_notification_delivery where id=1002"));
            assertEquals("COMPLETED", scalar(session,
                    "select status from club_event_notification_outbox where delivery_id=1001"));
            assertEquals("COMPLETED", scalar(session,
                    "select status from club_event_notification_outbox where delivery_id=1002"));
            verify(imService).sendSystem(eq(201L), eq(1), any(String.class), any(String.class));
        }
    }

    private ClubEventCampaign campaign(Long id, String requestId) {
        ClubEventCampaign row = new ClubEventCampaign();
        row.setId(id);
        row.setClubId(21L);
        row.setActivityId(71L);
        row.setAudienceType("WAITLIST");
        row.setChannel("IN_APP");
        row.setTitle("场次取消通知");
        row.setContent("活动已取消");
        row.setRequestId(requestId);
        return row;
    }

    private String scalar(SqlSession session, String sql) {
        try (Statement statement = session.getConnection().createStatement();
             java.sql.ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("missing row: " + sql);
            return result.getString(1);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
