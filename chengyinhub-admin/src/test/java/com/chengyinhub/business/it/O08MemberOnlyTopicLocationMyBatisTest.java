package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc2 O-08:「仅俱乐部成员可见」主题的坐标在地图图层与按 topicId 回查两条 SQL 上的真库判据。
 * 跑仓库里那份 CmsTopicMapper.xml(clubMemberOnlyGate 原文),不另抄一份判据。
 *
 * <p>负控:删掉 selectNearbyTopics 里新增的 {@code exists (... clubMemberOnlyGate)},
 * 或把 selectClubMemberOnlyHiddenTopicIds 的 {@code not} 去掉,下面的陌生人断言必须变红。
 */
class O08MemberOnlyTopicLocationMyBatisTest {
    private static final long PUBLIC_TOPIC = 11L;
    private static final long MEMBER_ONLY_TOPIC = 12L;
    private static final long STRANGER = 40L;
    private static final long ACTIVE_MEMBER = 8L;
    private static final long LEFT_MEMBER = 9L;
    private static final long CLUB_OWNER = 99L;
    private static final long TOPIC_AUTHOR = 7L;
    private static final long PAID_PLAYER = 30L;

    @Test
    void memberOnlyTopicCoordinatesStayOffStrangersMapButReachMembers() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession s = f.openSession(true)) {
            assertTrue(s.getConnection().isValid(2), "真实 MySQL fixture 必须可达");
            Statement st = s.getConnection().createStatement();
            recreate(st);
            seed(st);
            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);

            assertEquals(List.of(PUBLIC_TOPIC), nearbyIds(mapper, STRANGER), "陌生人只看到公开主题的落点");
            assertEquals(List.of(PUBLIC_TOPIC), nearbyIds(mapper, null), "未登录同样看不到");
            assertEquals(List.of(PUBLIC_TOPIC), nearbyIds(mapper, LEFT_MEMBER), "已退出的成员看不到");
            for (Long allowed : new Long[]{ACTIVE_MEMBER, CLUB_OWNER, TOPIC_AUTHOR, PAID_PLAYER}) {
                assertEquals(List.of(PUBLIC_TOPIC, MEMBER_ONLY_TOPIC), nearbyIds(mapper, allowed),
                        "成员/主理人/主题作者/已付款玩家照常看到:" + allowed);
            }
        }
    }

    @Test
    void hiddenTopicProbeMatchesTheSameGate() throws Exception {
        SqlSessionFactory f = factory();
        try (SqlSession s = f.openSession(true)) {
            Statement st = s.getConnection().createStatement();
            recreate(st);
            seed(st);
            CmsTopicMapper mapper = s.getMapper(CmsTopicMapper.class);
            List<Long> both = Arrays.asList(PUBLIC_TOPIC, MEMBER_ONLY_TOPIC);

            assertEquals(List.of(MEMBER_ONLY_TOPIC), mapper.selectClubMemberOnlyHiddenTopicIds(both, STRANGER));
            assertEquals(List.of(MEMBER_ONLY_TOPIC), mapper.selectClubMemberOnlyHiddenTopicIds(both, null));
            assertEquals(List.of(MEMBER_ONLY_TOPIC), mapper.selectClubMemberOnlyHiddenTopicIds(both, LEFT_MEMBER));
            for (Long allowed : new Long[]{ACTIVE_MEMBER, CLUB_OWNER, TOPIC_AUTHOR, PAID_PLAYER}) {
                assertEquals(Collections.emptyList(), mapper.selectClubMemberOnlyHiddenTopicIds(both, allowed),
                        "有权者不应被判成隐藏:" + allowed);
            }
        }
    }

    private List<Long> nearbyIds(CmsTopicMapper mapper, Long viewer) {
        List<Map<String, Object>> rows = mapper.selectNearbyTopics(
                31.2304, 121.4737, 31.0, 31.5, 121.2, 121.8, 50_000, 100, viewer);
        return rows.stream().map(r -> ((Number) r.get("id")).longValue()).sorted().collect(Collectors.toList());
    }

    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        PooledDataSource ds = new PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                MybatisItDatabase.url("o08_member_only_location"),
                MybatisItDatabase.user(), MybatisItDatabase.password());
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/CmsTopicMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/CmsTopicMapper.xml", cfg.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(cfg);
    }

    private void recreate(Statement st) throws Exception {
        st.execute("DROP TABLE IF EXISTS cms_topic");
        st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, club_id BIGINT,"
                + " product_type INT, circle_theme_code VARCHAR(24), name VARCHAR(120), img_url VARCHAR(255),"
                + " address_name VARCHAR(120), start_date DATETIME, end_date DATETIME, status TINYINT,"
                + " user_status TINYINT, del_flag TINYINT, is_template TINYINT,"
                + " club_member_only TINYINT NOT NULL DEFAULT 0)");
        st.execute("DROP TABLE IF EXISTS cms_topic_node");
        st.execute("CREATE TABLE cms_topic_node (id BIGINT PRIMARY KEY, topic_id BIGINT,"
                + " latitude VARCHAR(32), longitude VARCHAR(32), sort_id BIGINT)");
        st.execute("DROP TABLE IF EXISTS ums_member");
        st.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, role VARCHAR(20), nickname VARCHAR(64))");
        st.execute("DROP TABLE IF EXISTS club");
        st.execute("CREATE TABLE club (id BIGINT PRIMARY KEY, member_id BIGINT, del_flag INT)");
        st.execute("DROP TABLE IF EXISTS club_member");
        st.execute("CREATE TABLE club_member (id BIGINT PRIMARY KEY, club_id BIGINT, member_id BIGINT,"
                + " status INT, del_flag INT)");
        st.execute("DROP TABLE IF EXISTS cms_activity");
        st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                + " del_flag TINYINT NOT NULL DEFAULT 0)");
        st.execute("DROP TABLE IF EXISTS cms_registration");
        st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, owner_type INT, owner_id BIGINT,"
                + " member_id BIGINT, topic_id BIGINT, del_flag INT NOT NULL DEFAULT 0,"
                + " registration_status INT, payment_status INT)");
    }

    private void seed(Statement st) throws Exception {
        st.execute("INSERT INTO club (id,member_id,del_flag) VALUES (5," + CLUB_OWNER + ",0)");
        st.execute("INSERT INTO club_member (id,club_id,member_id,status,del_flag) VALUES"
                + " (1,5," + ACTIVE_MEMBER + ",1,0),(2,5," + LEFT_MEMBER + ",0,0)");
        st.execute("INSERT INTO cms_topic (id,member_id,club_id,product_type,name,status,user_status,del_flag,"
                + "is_template,club_member_only) VALUES"
                + " (" + PUBLIC_TOPIC + "," + TOPIC_AUTHOR + ",NULL,1,'公开主题',1,1,0,0,0),"
                + " (" + MEMBER_ONLY_TOPIC + "," + TOPIC_AUTHOR + ",5,1,'仅成员主题',1,1,0,0,1)");
        st.execute("INSERT INTO cms_topic_node (id,topic_id,latitude,longitude,sort_id) VALUES"
                + " (101," + PUBLIC_TOPIC + ",'31.2310','121.4740',1),"
                + " (102," + MEMBER_ONLY_TOPIC + ",'31.2320','121.4750',1)");
        st.execute("INSERT INTO cms_activity (id,topic_id,club_id,del_flag) VALUES (300," + MEMBER_ONLY_TOPIC + ",5,0)");
        st.execute("INSERT INTO cms_registration (id,owner_type,owner_id,member_id,topic_id,registration_status,"
                + "payment_status) VALUES (1,2,300," + PAID_PLAYER + ",NULL,2,2)");
    }
}
