package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.TopicChapterLoadFact;
import com.chengyinhub.business.domain.dto.TopicOnsiteTicketFact;
import com.chengyinhub.business.domain.dto.TopicWaitFact;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 真 MyBatis + H2 验证现场时间/店内负载查询；不冒充真实 MySQL 或生产证据。 */
class TopicOnsiteMetricsMapperMyBatisTest {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:onsite_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/TopicOnsiteMetricsMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table cms_registration(id bigint primary key, member_id bigint, owner_type bigint, owner_id bigint, ticket_id bigint, purchase_kind int, payment_status int, registration_status int, payment_time timestamp)");
            s.execute("create table cms_activity(id bigint primary key, topic_id bigint)");
            s.execute("create table oms_ticket(id bigint primary key, owner_type bigint, owner_id bigint, start_time timestamp, end_time timestamp, del_flag int)");
            s.execute("create table refund_application(source_type varchar(32), source_id bigint, payout_status int, del_flag int, create_time timestamp)");
            s.execute("create table cms_topic_chapter(id bigint primary key, topic_id bigint, required int)");
            s.execute("create table cms_topic(id bigint primary key, club_id bigint)");
            s.execute("create table club(id bigint primary key, member_id bigint)");
            s.execute("create table ums_member_follow(member_id bigint, follow_member_id bigint, create_time timestamp)");
            s.execute("create table club_member(club_id bigint, member_id bigint, status int, del_flag int, join_time timestamp)");
            s.execute("create table registration_entitlement(registration_id bigint, chapter_id bigint, topic_id bigint, status int)");
            s.execute("create table cms_topic_node(id bigint primary key, chapter_id bigint, topic_id bigint)");
            s.execute("create table player_node_progress(member_id bigint, activity_id bigint, topic_id bigint, node_id bigint, checkin_time timestamp, del_flag int)");
            s.execute("create table redemption(id bigint primary key, registration_id bigint, chapter_id bigint, topic_id bigint, status varchar(16), create_time timestamp)");
            s.execute("create table coop_complaint(id bigint primary key, topic_id bigint, registration_id bigint, reporter_member_id bigint, status int, fault_ratio decimal(5,4), del_flag int)");
            s.execute("insert into cms_registration values(21,9,2,11,71,3,2,2,timestamp '2026-08-14 08:00:00')");
            s.execute("insert into cms_activity values(11,7)");
            s.execute("insert into oms_ticket values(71,2,11,timestamp '2026-08-15 09:00:00',timestamp '2026-08-15 18:00:00',0)");
            s.execute("insert into cms_topic_chapter values(31,7,1)");
            s.execute("insert into cms_topic values(7,81)");
            s.execute("insert into club values(81,91)");
            s.execute("insert into registration_entitlement values(21,31,7,0)");
            s.execute("insert into cms_topic_node values(41,31,7)");
            s.execute("insert into player_node_progress values(9,11,7,41,timestamp '2026-08-15 10:00:00',0)");
            s.execute("insert into redemption values(51,21,31,7,'ACTIVE',timestamp '2026-08-15 10:12:00')");
        }
    }

    @Test
    void readsQueueAndServiceTimesFromTheirOnlyApprovedSources() {
        try (SqlSession session = factory.openSession(true)) {
            List<TopicWaitFact> facts = session.getMapper(TopicOnsiteMetricsMapper.class).selectWaitFacts(7L);
            assertEquals(1, facts.size());
            assertEquals(12 * 60_000L,
                    facts.get(0).getServiceStartedAt().getTime() - facts.get(0).getQueueJoinedAt().getTime());
        }
    }

    @Test
    void inStoreLoadCountsArrivedPendingEntitlementWithoutQuotaColumns() {
        try (SqlSession session = factory.openSession(true)) {
            List<TopicChapterLoadFact> loads = session.getMapper(TopicOnsiteMetricsMapper.class).selectInStoreLoads(7L);
            assertEquals(1, loads.size());
            assertEquals(1, loads.get(0).getInStoreLoad());
        }
    }

    @Test
    void ticketFactsExecuteAgainstRealMapperAndIncludeAllFourTicketSkus() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("insert into refund_application values('registration',21,1,0,timestamp '2026-08-15 10:30:00')");
            for (int i = 2; i <= 4; i++) {
                long activityId = 10L + i;
                long ticketId = 70L + i;
                long registrationId = 20L + i;
                s.execute("insert into cms_activity values(" + activityId + ",7)");
                s.execute("insert into oms_ticket values(" + ticketId + ",2," + activityId
                        + ",timestamp '2026-08-15 " + (8 + i) + ":00:00',timestamp '2026-08-15 18:00:00',0)");
                s.execute("insert into cms_registration values(" + registrationId + "," + (8 + i)
                        + ",2," + activityId + "," + ticketId
                        + ",3,2,2,timestamp '2026-08-14 08:00:00')");
            }
            List<TopicOnsiteTicketFact> facts = session.getMapper(TopicOnsiteMetricsMapper.class).selectTicketFacts(7L);
            assertEquals(4, facts.size());
            assertNotNull(facts.stream().filter(f -> Long.valueOf(21L).equals(f.getRegistrationId()))
                    .findFirst().orElseThrow(AssertionError::new).getRefundTime());
            assertTrue(facts.stream().anyMatch(f -> Long.valueOf(24L).equals(f.getRegistrationId())),
                    "四票种必须同时进入漏斗，禁止 limit 3 隐藏第四场");

            s.executeUpdate("update refund_application set payout_status=0 where source_id=21");
            session.clearCache();
            TopicOnsiteTicketFact mutated = session.getMapper(TopicOnsiteMetricsMapper.class).selectTicketFacts(7L)
                    .stream().filter(f -> Long.valueOf(21L).equals(f.getRegistrationId()))
                    .findFirst().orElseThrow(AssertionError::new);
            assertNull(mutated.getRefundTime(), "负控：只有实际退款责任状态才允许读成退款事实");
            assertNotNull(mutated.getRefundRequestedAt(), "退款申请时刻与实际退款责任必须分列，不能一起抹掉");
        }
    }

    @Test
    void mutationControlChangingCounterpartRedemptionTimeChangesReadback() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.executeUpdate("update redemption set create_time=timestamp '2026-08-15 09:59:00' where id=51");
            TopicWaitFact fact = session.getMapper(TopicOnsiteMetricsMapper.class).selectWaitFacts(7L).get(0);
            assertTrue(fact.getServiceStartedAt().before(fact.getQueueJoinedAt()),
                    "负控必须从 redemption 对面读回，并让等待样本变成负数");
        }
    }

    @Test
    void effectiveComplaintCountsOnlyClosedAtFaultDistinctReporters() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("insert into coop_complaint values"
                    + "(1,7,21,101,3,0.5000,0),"
                    + "(2,7,21,101,3,1.0000,0),"
                    + "(3,7,21,102,2,1.0000,0),"
                    + "(4,7,21,103,3,0.0000,0)");

            assertEquals(1, session.getMapper(TopicOnsiteMetricsMapper.class)
                    .countEffectiveComplaintUsers(7L));
        }
    }
}
