package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventDelivery;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import java.io.InputStream;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubEventNotificationClockMyBatisTest {
    @ParameterizedTest
    @ValueSource(longs = { -28800000L, 28800000L })
    void dueSelectionAndLeaseUseTheSameApplicationClock(long offsetMillis) throws Exception {
        PooledDataSource source = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:notice_clock_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        try {
            Configuration configuration = new Configuration(new Environment(
                    "notice-clock", new JdbcTransactionFactory(), source));
            String resource = "mapper/business/ClubEventOpsMapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
            try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
                try (Statement statement = session.getConnection().createStatement()) {
                    statement.execute("create table club_event_notification_delivery ("
                            + "id bigint auto_increment primary key, campaign_id bigint, recipient_member_id bigint,"
                            + "channel varchar(32), status varchar(32), attempt_count int, version int,"
                            + "message_id bigint, last_error varchar(500), sent_at timestamp,"
                            + "create_time timestamp, update_time timestamp)");
                    statement.execute("create table club_event_notification_outbox (delivery_id bigint primary key,"
                            + "status varchar(32), next_attempt_at timestamp, create_time timestamp, update_time timestamp)");
                }
                ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);
                ClubEventDelivery delivery = new ClubEventDelivery();
                delivery.setCampaignId(9L);
                delivery.setRecipientMemberId(51L);
                delivery.setChannel("IN_APP");
                delivery.setStatus("PENDING");
                delivery.setAttemptCount(0);
                delivery.setVersion(0);
                assertEquals(1, mapper.insertDelivery(delivery));
                Date due = new Date(System.currentTimeMillis() / 1000 * 1000 + offsetMillis);
                Date before = new Date(due.getTime() - 1000);
                Date lease = new Date(due.getTime() + 60000);
                assertEquals(1, mapper.insertOutbox(delivery.getId(), due));
                String namespace = ClubEventOpsMapper.class.getName() + ".";

                assertTrue(mapper.selectDueCampaignIds(before, 10).isEmpty());
                assertTrue(session.selectList(namespace + "selectDispatchableByCampaign",
                        Map.of("campaignId", 9L, "limit", 50, "now", before)).isEmpty(),
                        "a database clock ahead of the application must not dispatch early");
                assertEquals(0, session.update(namespace + "claimNotificationOutbox",
                        Map.of("deliveryId", delivery.getId(), "leaseUntil", lease, "now", before)));

                assertEquals(List.of(9L), mapper.selectDueCampaignIds(due, 10));
                List<ClubEventDelivery> ready = session.selectList(namespace + "selectDispatchableByCampaign",
                        Map.of("campaignId", 9L, "limit", 50, "now", due));
                assertEquals(List.of(delivery.getId()), ready.stream().map(ClubEventDelivery::getId).toList(),
                        "a due campaign must remain claimable when the database clock is behind");
                assertEquals(1, session.update(namespace + "claimNotificationOutbox",
                        Map.of("deliveryId", delivery.getId(), "leaseUntil", lease, "now", due)));
                assertEquals(0, session.update(namespace + "claimNotificationOutbox",
                        Map.of("deliveryId", delivery.getId(), "leaseUntil", lease, "now", due)),
                        "an unexpired lease cannot be claimed twice");
                assertTrue(mapper.selectDueCampaignIds(due, 10).isEmpty());

                Date expired = new Date(lease.getTime() + 1000);
                assertEquals(List.of(9L), mapper.selectDueCampaignIds(expired, 10));
                assertEquals(1, session.update(namespace + "claimNotificationOutbox",
                        Map.of("deliveryId", delivery.getId(), "leaseUntil",
                                new Date(expired.getTime() + 60000), "now", expired)),
                        "an expired lease remains recoverable");
            }
        } finally {
            source.forceCloseAll();
        }
    }
}
