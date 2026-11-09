package com.chengyinhub.business.mapper;

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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第十二轮 k9(p3 N16):群发通知「近 90 天未活跃」不能把今天入会、今天在本俱乐部主题/自办场次买了票的成员算进去。
 * 原 SQL 只认挂 club_id 的活动场次单 + payment_time,3 个今天入会并付款的成员里报出 2 个「未活跃」。
 */
class ClubInactiveRecipientsMyBatisTest {
    private static final long CLUB = 21L;
    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_inactive_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            // H2 不认 MySQL 的 date_sub(now(), interval 90 day);只把这一种写法换成 H2 的 dateadd,生产 XML 不动。
            String mysqlXml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String window = "date_sub(now(), interval 90 day)";
            assertTrue(mysqlXml.split(Pattern.quote(window), -1).length - 1 >= 3,
                    "inactiveMemberCondition 的 90 天窗口写法变了,复核这个 H2 适配");
            String h2Xml = mysqlXml.replace(window, "dateadd('DAY', -90, now())");
            new XMLMapperBuilder(new StringReader(h2Xml), configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            st.execute("create table club (id bigint primary key, member_id bigint, status int, del_flag int, "
                    + "create_time timestamp)");
            st.execute("create table club_member (club_id bigint, member_id bigint, status int, del_flag int, "
                    + "join_time timestamp)");
            st.execute("create table club_governance_ban (club_id bigint, target_member_id bigint, "
                    + "status varchar(16), source_type varchar(16), expires_at timestamp)");
            st.execute("create table cms_topic (id bigint primary key, club_id bigint, member_id bigint, del_flag int)");
            st.execute("create table cms_activity (id bigint primary key, club_id bigint, topic_id bigint, "
                    + "member_id bigint, del_flag int)");
            st.execute("create table cms_registration (id bigint primary key, owner_type int, owner_id bigint, "
                    + "member_id bigint, payment_status int, registration_status int, payment_time timestamp, "
                    + "create_time timestamp)");

            String old = "dateadd('DAY', -200, current_timestamp)";
            // 俱乐部 200 天前建立,主理人 42
            st.execute("insert into club values (21, 42, 1, 0, " + old + ")");
            // 本俱乐部主题 31(主理人 42 发布);别的俱乐部 99 的主题 39
            st.execute("insert into cms_topic values (31, 21, 42, 0), (39, 99, 77, 0)");
            // 71 本俱乐部场次;72 主题发布者自办场次(club_id 为空);79 别的俱乐部场次
            st.execute("insert into cms_activity values (71, 21, 31, 42, 0), (72, null, 31, 42, 0), (79, 99, 39, 77, 0)");
            st.execute("insert into club_member values "
                    + "(21, 201, 1, 0, current_timestamp),"   // 今天入会,还没报名
                    + "(21, 202, 1, 0, " + old + "),"          // 老成员,今天买了本俱乐部主题票(payment_time 为空)
                    + "(21, 203, 1, 0, " + old + "),"          // 老成员,今天买了主题自办场次票
                    + "(21, 204, 1, 0, " + old + "),"          // 老成员,只在别的俱乐部买过票
                    + "(21, 205, 1, 0, " + old + "),"          // 老成员,没有任何报名
                    + "(21, 206, 1, 0, " + old + "),"          // 老成员,近期的单已退款
                    + "(21, 207, 1, 0, " + old + ")");         // 老成员,本俱乐部场次今天付款
            st.execute("insert into cms_registration values "
                    + "(1, 1, 31, 202, 2, 2, null, current_timestamp),"
                    + "(2, 2, 72, 203, 2, 2, current_timestamp, current_timestamp),"
                    + "(3, 2, 79, 204, 2, 2, current_timestamp, current_timestamp),"
                    + "(4, 2, 71, 206, 4, 2, current_timestamp, current_timestamp),"
                    + "(5, 2, 71, 207, 2, 2, current_timestamp, current_timestamp)");
        }
    }

    @Test
    void recentJoinersAndRecentPayersInClubScopeAreNotInactive() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);
            assertEquals(Arrays.asList(42L, 204L, 205L, 206L), mapper.selectInactiveRecipients(CLUB));
        }
    }

    @Test
    void sendTimeRecheckUsesTheSameInactiveContract() {
        try (SqlSession session = factory.openSession(true)) {
            ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);
            for (long active : new long[]{201L, 202L, 203L, 207L}) {
                assertEquals(0, mapper.isEligibleNotificationRecipient(1L, CLUB, null, "INACTIVE", active),
                        "member " + active + " is active");
            }
            for (long inactive : new long[]{42L, 204L, 205L, 206L}) {
                assertEquals(1, mapper.isEligibleNotificationRecipient(1L, CLUB, null, "INACTIVE", inactive),
                        "member " + inactive + " is inactive");
            }
        }
    }

    @Test
    void recentlyCreatedClubOwnerIsNotInactive() throws Exception {
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement()) {
            st.execute("update club set create_time = current_timestamp where id = 21");
            assertEquals(Arrays.asList(204L, 205L, 206L),
                    session.getMapper(ClubEventOpsMapper.class).selectInactiveRecipients(CLUB));
        }
    }
}
