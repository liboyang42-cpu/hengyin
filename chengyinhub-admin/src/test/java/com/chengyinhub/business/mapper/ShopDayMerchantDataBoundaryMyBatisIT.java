package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.dto.ShopDayMerchantRosterFact;
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
import java.util.Date;
import java.util.List;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 MyBatis + H2 验证本店/本活动读 scope 与 append-only 单店同意；不冒充真实 MySQL。 */
class ShopDayMerchantDataBoundaryMyBatisIT {
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:shopday_roster_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("ConsentRecord", ConsentRecord.class);
        parse(configuration, "mapper/business/ShopDayMerchantRosterMapper.xml");
        parse(configuration, "mapper/business/ConsentRecordMapper.xml");
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            s.execute("create table cms_topic(id bigint primary key, product_type int, del_flag int)");
            s.execute("create table merchant_chapter_offer(id bigint primary key, topic_id bigint, chapter_id bigint, merchant_id bigint, status int, valid_end timestamp)");
            s.execute("create table mms_merchant(member_id bigint primary key, status bigint, account_status bigint, del_flag int)");
            s.execute("create table registration_entitlement(registration_id bigint, chapter_id bigint, topic_id bigint, status int)");
            s.execute("create table cms_registration(id bigint primary key, registration_no varchar(64), member_id bigint, owner_type bigint, owner_id bigint, ticket_id bigint, purchase_kind int, payment_status int, registration_status int, real_name varchar(64), phone varchar(32))");
            s.execute("create table cms_activity(id bigint primary key, topic_id bigint)");
            s.execute("create table oms_ticket(id bigint primary key, owner_type bigint, owner_id bigint, start_time timestamp, end_time timestamp, del_flag int)");
            s.execute("create table ums_member(id bigint primary key, nickname varchar(64), avatar varchar(255), del_flag int)");
            s.execute("create table cms_topic_node(id bigint primary key, topic_id bigint, chapter_id bigint, merchant_member_id bigint, node_audit_status int)");
            s.execute("create table player_node_progress(id bigint primary key, member_id bigint, activity_id bigint, topic_id bigint, node_id bigint, checkin_time timestamp, del_flag int)");
            s.execute("create table redemption(id bigint primary key, registration_id bigint, topic_id bigint, chapter_id bigint, merchant_id bigint, status varchar(16), create_time timestamp)");
            s.execute("create table consent_record(id bigint auto_increment primary key, member_id bigint, doc_type varchar(64), doc_version varchar(32), scene varchar(32), scope_type varchar(16), scope_id bigint, event_type varchar(16), occurred_at timestamp, request_id varchar(64), ip_address varchar(64), create_time timestamp)");

            s.execute("insert into cms_topic values(23,2,0)");
            s.execute("insert into merchant_chapter_offer values(1,23,31,2001,1,null),(2,23,32,2002,1,null)");
            s.execute("insert into mms_merchant values(2001,1,1,0),(2002,1,1,0)");
            s.execute("insert into cms_activity values(11,23)");
            s.execute("insert into oms_ticket values(71,2,11,timestamp '2026-08-15 09:00:00',timestamp '2026-08-15 18:00:00',0)");
            s.execute("insert into cms_registration values(301,'TD-301',9001,2,11,71,3,2,2,'甲玩家','13800138001'),(302,'TD-302',9002,2,11,71,3,2,2,'乙玩家','13800138002'),(303,'TD-303',9003,2,11,71,3,2,2,'丙玩家','13800138003')");
            s.execute("insert into ums_member values(9001,'甲','a.png',0),(9002,'乙','b.png',0),(9003,'丙','c.png',0)");
            s.execute("insert into registration_entitlement values(301,31,23,0),(302,32,23,0),(303,31,23,0)");
            s.execute("insert into cms_topic_node values(41,23,31,2001,1),(42,23,32,2002,1)");
            s.execute("insert into player_node_progress values(51,9001,11,23,41,timestamp '2026-08-15 10:00:00',0),(52,9002,11,23,42,timestamp '2026-08-15 10:01:00',0)");
            s.execute("insert into redemption values(61,303,23,31,2001,'ACTIVE',timestamp '2026-08-15 10:05:00')");
        }
    }

    @Test
    void rosterIsIntersectionOfOwnStoreOwnTopicAndArrivalOrRedemption() throws Exception {
        try (SqlSession session = factory.openSession(true); Statement s = session.getConnection().createStatement()) {
            ShopDayMerchantRosterMapper mapper = session.getMapper(ShopDayMerchantRosterMapper.class);
            List<ShopDayMerchantRosterFact> merchantA = mapper.selectRoster(23L, 2001L);
            assertEquals(2, merchantA.size());
            assertTrue(merchantA.stream().anyMatch(f -> Long.valueOf(301L).equals(f.getRegistrationId())));
            assertTrue(merchantA.stream().anyMatch(f -> Long.valueOf(303L).equals(f.getRegistrationId())));
            assertTrue(merchantA.stream().noneMatch(f -> Long.valueOf(302L).equals(f.getRegistrationId())));

            s.executeUpdate("update cms_topic_node set node_audit_status=0 where id=41");
            session.clearCache();
            assertEquals(2, mapper.selectRoster(23L, 2001L).size(),
                    "服务中队列已由到店事实锁定，节点重审不得让玩家消失");

            s.executeUpdate("update cms_topic_node set merchant_member_id=2002 where id=41");
            session.clearCache();
            List<ShopDayMerchantRosterFact> mutated = mapper.selectRoster(23L, 2001L);
            assertEquals(1, mutated.size(), "负控：把进店节点改归对店后，本店必须立即少一行");
            assertEquals(303L, mutated.get(0).getRegistrationId());

            s.executeUpdate("update mms_merchant set account_status=2 where member_id=2001");
            session.clearCache();
            assertEquals(0, mapper.countLiveMerchantScope(23L, 2001L), "停用商家不得保留名单读取能力");
        }
    }

    @Test
    void rosterPiiColumnsUseTheEncryptedReadTypeHandler() throws Exception {
        try (InputStream input = Resources.getResourceAsStream(
                "mapper/business/ShopDayMerchantRosterMapper.xml")) {
            String xml = new Scanner(input, "UTF-8").useDelimiter("\\A").next();
            String handler = "typeHandler=\"com.chengyinhub.business.security.PiiCryptoTypeHandler\"";
            assertTrue(xml.matches("(?s).*property=\"realName\"[^>]*" + handler + ".*"));
            assertTrue(xml.matches("(?s).*property=\"phone\"[^>]*" + handler + ".*"));
        }
    }

    @Test
    void latestConsentIsIsolatedPerMerchantAndAppendOnlyRevokeWins() {
        try (SqlSession session = factory.openSession(true)) {
            ConsentRecordMapper mapper = session.getMapper(ConsentRecordMapper.class);
            insert(mapper, "AGREE", 2001L, "agree-a", new Date(1000L));
            assertEquals("AGREE", mapper.selectLatestScoped(9001L, "merchant_onsite_data_sharing",
                    "merchant_redeem", "MERCHANT", 2001L).getEventType());
            assertNull(mapper.selectLatestScoped(9001L, "merchant_onsite_data_sharing",
                    "merchant_redeem", "MERCHANT", 2002L));

            insert(mapper, "REVOKE", 2001L, "revoke-a", new Date(2000L));
            assertEquals("REVOKE", mapper.selectLatestScoped(9001L, "merchant_onsite_data_sharing",
                    "merchant_redeem", "MERCHANT", 2001L).getEventType());
        }
    }

    private static void insert(ConsentRecordMapper mapper, String event, Long merchantId, String requestId, Date at) {
        ConsentRecord record = new ConsentRecord();
        record.setMemberId(9001L);
        record.setDocType("merchant_onsite_data_sharing");
        record.setDocVersion("v1.0");
        record.setScene("merchant_redeem");
        record.setScopeType("MERCHANT");
        record.setScopeId(merchantId);
        record.setEventType(event);
        record.setOccurredAt(at);
        record.setRequestId(requestId);
        record.setCreateTime(at);
        mapper.insertConsentRecord(record);
    }

    private static void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }
}
