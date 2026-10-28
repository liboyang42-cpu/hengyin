package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.MyAppealRow;
import com.chengyinhub.business.domain.ThemeAttributeMark;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicPlaySetup;
import com.chengyinhub.business.mapper.MemberAccountMapper;
import com.chengyinhub.business.mapper.ThemeAttributeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicPlaySetupMapper;
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
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档二 L8 新 Mapper XML 与两份迁移在真 MySQL 8 上的回读(一类一库,无可用性跳过)。
 * 迁移灌真实 DDL;cms_topic / 计分申诉 / 封禁只建本类用到的列。
 */
class Doc2L8MapperMyBatisIT {

    private static final String[] XMLS = {
            "mapper/business/ThemeStateKeyMapper.xml",
            "mapper/business/ThemeAttributeMapper.xml",
            "mapper/business/TopicPlaySetupMapper.xml",
            "mapper/business/MemberAccountMapper.xml"};

    private SqlSession open() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("ThemeStateKey", ThemeStateKey.class);
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url("doc2_l8_mapper"),
                MybatisItDatabase.user(), MybatisItDatabase.password())));
        for (String xml : XMLS) {
            try (InputStream in = Resources.getResourceAsStream(xml)) {
                new XMLMapperBuilder(in, cfg, xml, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg).openSession(true);
    }

    @Test
    void attributeMigrationAdoptsLegacyCountersAndIsRepeatable() throws Exception {
        try (SqlSession session = open()) {
            ItSchema.applyMigrations(session.getConnection(), "sql/migration_theme_state_key_20260916.sql",
                    "sql/migration_20261022_theme_state_key_attribute_def.sql",
                    "sql/migration_20261026_theme_attribute_registry_version.sql");
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("drop table if exists cms_topic");
                st.execute("create table cms_topic(id bigint primary key, name varchar(64), journey_rules_json longtext)");
                st.execute("insert into cms_topic values(7,'雾港夜行','{\"schemaVersion\":1,\"stateEnabled\":true,\"attributes\":["
                        + "{\"key\":\"counter.focus\",\"label\":\"专注\",\"initial\":2,\"min\":0,\"max\":12,\"visible\":true},"
                        + "{\"key\":\"counter.stamina\",\"label\":\"体力\",\"initial\":3,\"min\":0,\"max\":9,\"visible\":false},"
                        + "{\"key\":\"counter.alarm\",\"label\":\"警觉\",\"initial\":0,\"min\":0,\"max\":6,\"visible\":true},"
                        + "{\"key\":\"counter.bad\",\"label\":\"坏\",\"initial\":9,\"min\":0,\"max\":5,\"visible\":true},"
                        + "{\"key\":\"tag.newcomer\",\"label\":\"新来的\",\"initial\":1,\"min\":0,\"max\":1,\"visible\":true}],"
                        + "\"rpg\":{\"clocks\":[{\"var\":\"counter.alarm\",\"label\":\"警觉\",\"segments\":6}]}}')");
                st.execute("insert into cms_topic values(8,'坏数据','not json')");
                st.execute("insert into cms_topic values(9,'没规则',null)");
                st.execute("insert into theme_state_key(topic_id,state_key,key_type,scope,label,initial_value,create_time,update_time)"
                        + " values(7,'counter.focus','counter','run','已注册的专注',1,now(),now())");
            }
            for (int i = 0; i < 2; i++) {
                ItSchema.applyMigrations(session.getConnection(), "sql/migration_20261028_d20_attribute_registry.sql");
            }
            ThemeAttributeMapper marks = session.getMapper(ThemeAttributeMapper.class);
            List<ThemeAttributeMark> rows = marks.selectAttributeMarks(7L);
            assertEquals(2, rows.size());
            assertEquals("counter.focus", rows.get(0).getStateKey());
            assertEquals("custom", rows.get(0).getPreset());
            assertEquals("counter.stamina", rows.get(1).getStateKey());
            ThemeStateKeyMapper keys = session.getMapper(ThemeStateKeyMapper.class);
            ThemeStateKey focus = keys.selectByTopicIdAndStateKey(7L, "counter.focus");
            assertEquals("已注册的专注", focus.getLabel());
            assertEquals(Integer.valueOf(1), focus.getInitialValue());
            ThemeStateKey stamina = keys.selectByTopicIdAndStateKey(7L, "counter.stamina");
            assertEquals("体力", stamina.getLabel());
            assertEquals(Integer.valueOf(3), stamina.getInitialValue());
            assertEquals(Integer.valueOf(9), stamina.getMaxValue());
            assertEquals(Boolean.FALSE, stamina.getVisible());
            assertNull(keys.selectByTopicIdAndStateKey(7L, "counter.alarm"));
            assertNull(keys.selectByTopicIdAndStateKey(7L, "counter.bad"));
            assertNull(keys.selectByTopicIdAndStateKey(7L, "tag.newcomer"));
            assertEquals(2, keys.selectByTopicId(7L).size());

            keys.upsertThemeStateKey(key("counter.observe"));
            assertEquals(1, marks.markAttribute(7L, "counter.observe", "observe"));
            assertEquals(0, marks.markAttribute(7L, "counter.none", "observe"));
            assertEquals("observe", marks.selectAttributeMarks(7L).get(2).getPreset());
            try (Statement st = session.getConnection().createStatement();
                 ResultSet column = st.executeQuery("select column_default, is_nullable from information_schema.columns"
                         + " where table_schema = database() and table_name = 'theme_state_key' and column_name = 'is_attribute'")) {
                assertTrue(column.next());
                assertEquals("0", column.getString(1));
                assertEquals("NO", column.getString(2));
            }
        }
    }

    @Test
    void playSetupUpsertsWholeRow() throws Exception {
        try (SqlSession session = open()) {
            ItSchema.applyMigrations(session.getConnection(), "sql/migration_20261028_topic_play_setup.sql");
            TopicPlaySetupMapper mapper = session.getMapper(TopicPlaySetupMapper.class);
            assertNull(mapper.selectByTopicId(7L));
            TopicPlaySetup setup = new TopicPlaySetup();
            setup.setTopicId(7L);
            setup.setWindowMode("custom");
            setup.setWindowStart("21:30");
            setup.setWindowEnd("05:00");
            setup.setPrepItemsJson("[\"手机电量充足\",\"带手电筒\"]");
            setup.setUpdateTime(new Date());
            assertEquals(1, mapper.upsert(setup));
            TopicPlaySetup read = mapper.selectByTopicId(7L);
            assertEquals("custom", read.getWindowMode());
            assertEquals("21:30", read.getWindowStart());
            assertEquals("[\"手机电量充足\",\"带手电筒\"]", read.getPrepItemsJson());
            assertNotNull(read.getCreateTime());
            setup.setWindowMode("night");
            setup.setWindowStart(null);
            setup.setWindowEnd(null);
            setup.setPrepItemsJson(null);
            mapper.upsert(setup);
            read = mapper.selectByTopicId(7L);
            assertEquals("night", read.getWindowMode());
            assertNull(read.getWindowStart());
            assertNull(read.getPrepItemsJson());
        }
    }

    @Test
    void myAppealsUnionAndEffectiveBans() throws Exception {
        try (SqlSession session = open()) {
            try (Statement st = session.getConnection().createStatement()) {
                for (String table : new String[] {"orient_score_appeal", "orient_result_dispute", "topic_route_session",
                        "cms_topic", "club_governance_ban"}) {
                    st.execute("drop table if exists " + table);
                }
                st.execute("create table cms_topic(id bigint primary key, name varchar(64))");
                st.execute("create table topic_route_session(id bigint primary key, topic_id bigint)");
                st.execute("create table orient_score_appeal(id bigint primary key, session_id bigint, member_id bigint,"
                        + " node_id bigint, status tinyint, resolution_note varchar(500), create_time datetime(3))");
                st.execute("create table orient_result_dispute(id bigint primary key, session_id bigint, member_id bigint,"
                        + " status tinyint, resolution_note varchar(500), create_time datetime(3))");
                st.execute("create table club_governance_ban(id bigint primary key, club_id bigint, target_member_id bigint,"
                        + " status varchar(16), source_type varchar(16), expires_at datetime)");
                st.execute("insert into cms_topic values(5,'城南旧事 · 夜行定向')");
                st.execute("insert into topic_route_session values(88001,5)");
                st.execute("insert into orient_score_appeal values(1,88001,9,3,2,'计分记录与现场一致','2026-10-28 20:00:00')");
                st.execute("insert into orient_score_appeal values(2,88001,10,3,0,null,'2026-10-28 20:30:00')");
                st.execute("insert into orient_result_dispute values(1,88001,9,0,null,'2026-10-28 21:10:00')");
                st.execute("insert into club_governance_ban values(1,1,9,'ACTIVE','CLUB',date_add(now(), interval 1 day))");
                st.execute("insert into club_governance_ban values(2,1,10,'ACTIVE','CLUB',date_sub(now(), interval 1 day))");
                st.execute("insert into club_governance_ban values(3,1,11,'ACTIVE','PLATFORM',null)");
                st.execute("insert into club_governance_ban values(4,1,12,'UNBANNED','PLATFORM',null)");
            }
            MemberAccountMapper mapper = session.getMapper(MemberAccountMapper.class);
            List<MyAppealRow> mine = mapper.selectMyAppeals(9L, null, 0, 20);
            assertEquals(2, mine.size());
            assertEquals("dispute", mine.get(0).getKind());
            assertEquals("城南旧事 · 夜行定向", mine.get(0).getTopicName());
            assertNull(mine.get(0).getNodeId());
            assertEquals("appeal", mine.get(1).getKind());
            assertEquals(Long.valueOf(3L), mine.get(1).getNodeId());
            assertEquals("计分记录与现场一致", mine.get(1).getResolutionNote());
            assertEquals(2, mapper.countMyAppeals(9L, null));
            assertEquals(1, mapper.countMyAppeals(9L, Boolean.TRUE));
            assertEquals("appeal", mapper.selectMyAppeals(9L, Boolean.FALSE, 0, 20).get(0).getKind());
            assertEquals(1, mapper.selectMyAppeals(9L, null, 1, 20).size());
            Date now = new Date();
            assertEquals(1, mapper.countEffectiveBans(9L, now));
            assertEquals(0, mapper.countEffectiveBans(10L, now));
            assertEquals(1, mapper.countEffectiveBans(11L, now));
            assertEquals(0, mapper.countEffectiveBans(12L, now));
        }
    }

    private static ThemeStateKey key(String name) {
        ThemeStateKey row = new ThemeStateKey();
        row.setTopicId(7L);
        row.setStateKey(name);
        row.setKeyType("counter");
        row.setScope("run");
        row.setLabel("观察");
        row.setInitialValue(2);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        return row;
    }
}
