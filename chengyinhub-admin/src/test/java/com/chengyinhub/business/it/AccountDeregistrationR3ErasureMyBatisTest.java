package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.AccountDeregistration;
import com.chengyinhub.business.mapper.AccountDeregistrationMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HIGH-S6:用真实 AccountDeregistrationMapper.xml 语句(H2 MySQL 模式)对 r3 个人数据表播种后擦除,
 * 断言注销会员(9)的行被删除/去标识化,其他会员(10)的行和须留存台账的积分数字不动。列名取自
 * migration_20261123_sys_citygame / 20261124_city_numbers_attr / 20261115_external_game_monetization /
 * 20261123_sys_random / migration_im。
 */
class AccountDeregistrationR3ErasureMyBatisTest {

    private static final long ME = 9L;
    private static final long OTHER = 10L;

    private SqlSession open() throws Exception {
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:dereg_r3_erasure;MODE=MySQL", "sa", "")));
        config.getTypeAliasRegistry().registerAlias("AccountDeregistration", AccountDeregistration.class);
        String resource = "mapper/business/AccountDeregistrationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        try (Statement st = session.getConnection().createStatement()) {
            for (String ddl : new String[] {
                    "create table roam_territory (territory_id char(36) primary key, owner_member_id bigint not null,"
                            + " latitude decimal(16,12) not null, longitude decimal(16,12) not null,"
                            + " claim_photo_url varchar(500) not null, status varchar(16) not null default 'ACTIVE',"
                            + " version bigint not null default 1)",
                    "create table roam_territory_checkin (checkin_id char(36) primary key, member_id bigint not null,"
                            + " owner_member_id bigint not null, latitude decimal(16,12) not null,"
                            + " longitude decimal(16,12) not null, photo_url varchar(500))",
                    "create table roam_territory_audit (id bigint auto_increment primary key, member_id bigint not null,"
                            + " verdict varchar(16) not null, latitude decimal(16,12), longitude decimal(16,12),"
                            + " accuracy_m double, distance_m double, detail_json varchar(2000))",
                    "create table city_claim_evidence (id bigint auto_increment primary key, member_id bigint not null,"
                            + " photo_asset_id varchar(500), lat decimal(16,12), lng decimal(16,12))",
                    "create table city_device_account (device_key varchar(128) not null, member_id bigint not null,"
                            + " primary key (device_key, member_id))",
                    "create table member_attribute (member_id bigint not null, attr_key varchar(16) not null,"
                            + " axp bigint not null default 0, primary key (member_id, attr_key))",
                    "create table member_title (member_id bigint not null, title_code varchar(64) not null,"
                            + " title_name varchar(64) not null, primary key (member_id, title_code))",
                    "create table member_archetype (member_id bigint primary key, code varchar(16) not null,"
                            + " name varchar(32) not null)",
                    "create table im_conversation (id bigint primary key, type tinyint not null, biz_key varchar(64) not null)",
                    "create table im_conversation_member (id bigint auto_increment primary key,"
                            + " conversation_id bigint not null, member_id bigint not null)",
                    "create table node_task_dispatch (id bigint auto_increment primary key, member_id bigint not null,"
                            + " pool_id bigint not null, node_id bigint not null)",
                    "create table city_monopoly_quote (quote_id char(36) primary key, member_id bigint not null,"
                            + " cost bigint not null default 0, payload_json varchar(2000))",
                    "create table city_monopoly_receipt (member_id bigint not null, request_id varchar(96) not null,"
                            + " action varchar(24) not null, response_json varchar(2000) not null,"
                            + " primary key (member_id, request_id))",
                    "create table city_yield_event (id bigint auto_increment primary key, holder_member_id bigint not null,"
                            + " visitor_member_id bigint not null, granted bigint not null, device_key varchar(128))",
                    "create table city_abuse_signal (id bigint auto_increment primary key, member_id bigint not null,"
                            + " kind varchar(16) not null, action varchar(16), detail_json varchar(2000))",
                    "create table external_game_play_session (session_id varchar(40) primary key,"
                            + " member_id bigint not null, xp_awarded int not null default 0, client_ip_hash char(64))",
                    // 须留存的台账:注销不得删除或改数
                    "create table city_points_flow (id bigint auto_increment primary key, member_id bigint not null,"
                            + " delta bigint not null)",
                    "create table member_attribute_ledger (id bigint auto_increment primary key,"
                            + " member_id bigint not null, raw bigint not null)",
            }) {
                st.execute(ddl);
            }
            for (long m : new long[] {ME, OTHER}) {
                st.execute("insert into roam_territory values ('t" + m + "', " + m + ", 31.2, 121.4, 'https://img/" + m + "', 'ACTIVE', 1)");
                st.execute("insert into roam_territory_checkin values ('c" + m + "', " + m + ", 99, 31.2, 121.4, 'https://img/c')");
                st.execute("insert into roam_territory_audit(member_id, verdict, latitude, longitude, accuracy_m, distance_m, detail_json)"
                        + " values (" + m + ", 'PASS', 31.2, 121.4, 5, 12, '{\"prevLat\":31.1}')");
                st.execute("insert into city_claim_evidence(member_id, photo_asset_id, lat, lng) values (" + m + ", 'p', 31.2, 121.4)");
                st.execute("insert into city_device_account values ('idfv-" + m + "', " + m + ")");
                st.execute("insert into member_attribute values (" + m + ", 'explore', 40)");
                st.execute("insert into member_title values (" + m + ", 'T1', '城主')");
                st.execute("insert into member_archetype values (" + m + ", 'EX', '探索者')");
                st.execute("insert into node_task_dispatch(member_id, pool_id, node_id) values (" + m + ", 1, 2)");
                st.execute("insert into city_monopoly_quote values ('q" + m + "', " + m + ", 30, '{\"lat\":31.2}')");
                st.execute("insert into city_monopoly_receipt values (" + m + ", 'r1', 'CLAIM', '{\"deedPhoto\":\"https://img\"}')");
                st.execute("insert into city_yield_event(holder_member_id, visitor_member_id, granted, device_key)"
                        + " values (99, " + m + ", 7, 'idfv-" + m + "')");
                st.execute("insert into city_abuse_signal(member_id, kind, action, detail_json) values (" + m + ", 'DEVICE', 'FLAG', '{\"device\":\"x\"}')");
                st.execute("insert into external_game_play_session values ('s" + m + "', " + m + ", 5, 'iphash')");
                st.execute("insert into city_points_flow(member_id, delta) values (" + m + ", 25)");
                st.execute("insert into member_attribute_ledger(member_id, raw) values (" + m + ", 40)");
            }
            st.execute("insert into im_conversation values (1, 4, 'roam_nearby'), (2, 1, '9_10'), (3, 2, 'sys_9')");
            st.execute("insert into im_conversation_member(conversation_id, member_id) values"
                    + " (1, 9), (1, 10), (2, 9), (2, 10), (3, 9)");
        }
        return session;
    }

    @Test
    void deregistrationStatementsEraseOnlyTheMembersR3PersonalData() throws Exception {
        try (SqlSession session = open()) {
            AccountDeregistrationMapper d = session.getMapper(AccountDeregistrationMapper.class);

            assertEquals(1, d.deleteTerritoryCheckins(ME));
            assertEquals(1, d.revokeTerritories(ME));
            assertEquals(1, d.scrubTerritoryAudits(ME));
            assertEquals(1, d.deleteClaimEvidence(ME));
            assertEquals(1, d.deleteDeviceAccounts(ME));
            assertEquals(1, d.deleteMemberAttributes(ME));
            assertEquals(1, d.deleteMemberTitles(ME));
            assertEquals(1, d.deleteMemberArchetype(ME));
            assertEquals(1, d.deleteInGameChannelMemberships(ME));
            assertEquals(1, d.deleteNodeTaskDispatch(ME));
            assertEquals(1, d.scrubMonopolyQuotes(ME));
            assertEquals(1, d.scrubMonopolyReceipts(ME));
            assertEquals(1, d.scrubYieldEventDevices(ME));
            assertEquals(1, d.scrubAbuseSignals(ME));
            assertEquals(1, d.scrubExternalGamePlaySessions(ME));

            try (Statement st = session.getConnection().createStatement()) {
                // 整行删除
                for (String table : new String[] {"roam_territory_checkin", "city_claim_evidence", "city_device_account",
                        "member_attribute", "member_title", "member_archetype", "node_task_dispatch"}) {
                    assertEquals(0, count(st, "select count(*) from " + table + " where member_id = 9"), table);
                    assertEquals(1, count(st, "select count(*) from " + table + " where member_id = 10"), table + " 他人");
                }
                // 游戏内频道成员删;单聊/系统会话成员保留
                assertEquals(0, count(st, "select count(*) from im_conversation_member where member_id = 9 and conversation_id = 1"));
                assertEquals(2, count(st, "select count(*) from im_conversation_member where member_id = 9"));
                assertEquals(2, count(st, "select count(*) from im_conversation_member where member_id = 10"));

                // 领地:转 REVOKED,清照片;他人领地不动
                try (ResultSet rs = st.executeQuery("select status, claim_photo_url, version from roam_territory where owner_member_id = 9")) {
                    rs.next();
                    assertEquals("REVOKED", rs.getString(1));
                    assertEquals("", rs.getString(2));
                    assertEquals(2, rs.getLong(3));
                }
                assertEquals(1, count(st, "select count(*) from roam_territory where owner_member_id = 10"
                        + " and status = 'ACTIVE' and claim_photo_url = 'https://img/10'"));

                // 留存台账:保留 member_id 与数字,只清个人字段
                try (ResultSet rs = st.executeQuery("select latitude, longitude, accuracy_m, detail_json, distance_m, verdict"
                        + " from roam_territory_audit where member_id = 9")) {
                    rs.next();
                    assertNull(rs.getObject(1));
                    assertNull(rs.getObject(2));
                    assertNull(rs.getObject(3));
                    assertNull(rs.getObject(4));
                    assertEquals(12d, rs.getDouble(5));
                    assertEquals("PASS", rs.getString(6));
                }
                assertEquals(1, count(st, "select count(*) from city_monopoly_quote where member_id = 9 and payload_json is null and cost = 30"));
                assertEquals(1, count(st, "select count(*) from city_monopoly_receipt where member_id = 9 and response_json = '{}' and action = 'CLAIM'"));
                assertEquals(1, count(st, "select count(*) from city_yield_event where visitor_member_id = 9 and device_key is null and granted = 7"));
                assertEquals(1, count(st, "select count(*) from city_abuse_signal where member_id = 9 and detail_json is null and action = 'FLAG'"));
                assertEquals(1, count(st, "select count(*) from external_game_play_session where member_id = 9 and client_ip_hash is null and xp_awarded = 5"));
                for (String table : new String[] {"roam_territory_audit", "city_monopoly_quote", "city_abuse_signal"}) {
                    assertEquals(1, count(st, "select count(*) from " + table + " where member_id = 10 and "
                            + ("city_monopoly_quote".equals(table) ? "payload_json" : "detail_json") + " is not null"), table + " 他人");
                }
                assertEquals(1, count(st, "select count(*) from city_monopoly_receipt where member_id = 10 and response_json <> '{}'"));
                assertEquals(1, count(st, "select count(*) from city_yield_event where visitor_member_id = 10 and device_key is not null"));
                assertEquals(1, count(st, "select count(*) from external_game_play_session where member_id = 10 and client_ip_hash is not null"));

                // 积分/属性台账不删不改
                assertEquals(25, count(st, "select sum(delta) from city_points_flow where member_id = 9"));
                assertEquals(40, count(st, "select sum(raw) from member_attribute_ledger where member_id = 9"));
            }
        }
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
