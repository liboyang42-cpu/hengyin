package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.BizFunnelDaily;
import com.chengyinhub.business.mapper.BizFunnelDailyMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 analytics_max_date 证据：selectCommunityQualifiedPilotMetric 在 MySQL 最大日期年份的真实返回。
 *
 * <p>只取证、不改产品代码。两种结束边界各跑同样三条查询（12-30 单日、12-31 单日、12-30~12-31 合并），
 * 每条查询后在同一连接上立刻 SHOW WARNINGS；另有普通日期对照组。断言写的是正确期望
 * （单日各 1、合并 2），MySQL 若返回别的值，本类就以失败记录缺陷。
 * <ul>
 *   <li>DATE_ADD：master 现行 BizFunnelDailyMapper.xml，经 MyBatis 原样加载。</li>
 *   <li>TIMESTAMPADD：PR #4 提交 528e9486 的同一语句，原文抄录在 {@link #CANDIDATE_528E9486}，
 *       并校验它与现行语句只差结束边界这一行。</li>
 * </ul>
 * 所有取证行以 {@code [F3-MAXDATE]} 开头打到标准输出，供 workflow 日志回读。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunnelMaxDateMySqlIT {
    private static final String RESOURCE = "mapper/business/BizFunnelDailyMapper.xml";
    private static final String STATEMENT = "selectCommunityQualifiedPilotMetric";
    private static final String DATE_ADD_LINE =
            "and event.occurred_at &lt; date_add(cast(#{endDate} as date), interval 1 day)";
    private static final String TIMESTAMPADD_LINE =
            "and event.occurred_at &lt; timestampadd(day, 1, cast(#{endDate} as date))";
    private static final String CANDIDATE_NAMESPACE = "evidence.pr4.c528e9486";

    /** 528e948631569ac94215c957e6c485e4ebfd722f:BizFunnelDailyMapper.xml 第 131-153 行原文。 */
    private static final String CANDIDATE_528E9486 = """
    <!-- 阶段 1 试点总分母：先跨整个查询窗口按 user-post 去重，再计算用户与覆盖面。 -->
    <select id="selectCommunityQualifiedPilotMetric" resultType="map">
        with qualified_user_posts as (
          select event.member_id, event.biz_id, post.author_id as current_author_id,
                 min(event.occurred_at) as first_qualified_at,
                 max(event.occurred_at) as last_qualified_at
          from biz_event_log event
          left join community_post post on post.id = event.biz_id
          where event.event_name = 'community_post_qualified_view'
            and event.biz_type = 'community_post'
            and event.member_id is not null
            and event.occurred_at &gt;= cast(#{startDate} as date)
            and event.occurred_at &lt; timestampadd(day, 1, cast(#{endDate} as date))
          group by event.member_id, event.biz_id, post.author_id
        )
        select count(distinct member_id) as qualified_user_count,
               count(1) as qualified_user_post_count,
               count(distinct biz_id) as qualified_post_count,
               count(distinct nullif(current_author_id, 0)) as current_qualified_author_count,
               min(first_qualified_at) as first_qualified_at,
               max(last_qualified_at) as last_qualified_at
        from qualified_user_posts
    </select>
""";

    private final MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("funnel_max_date");
    private JdbcTemplate admin;
    private DriverManagerDataSource source;
    private boolean created;
    private String productionXml;

    @BeforeAll
    void setup() throws Exception {
        admin = new JdbcTemplate(source(config.adminUrl()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        admin.execute("CREATE DATABASE " + config.databaseName() + " CHARACTER SET utf8mb4");
        created = true;
        source = source(config.databaseUrl());
        JdbcTemplate db = new JdbcTemplate(source);
        Path root = Files.exists(Paths.get("sql/base_schema_20260818.sql")) ? Paths.get("") : Paths.get("..");
        db.execute(ddl(Files.readString(root.resolve("sql/base_schema_20260818.sql")),
                "CREATE TABLE `biz_event_log` ("));
        // community_post 不在 0818 基线快照里；它的建表语句以平台迁移为准（语句 left join 它）。
        // 只去掉指向 community_guideline_version 的外键：语句不读那张表，不为它多建一张表。
        String postDdl = ddl(Files.readString(root.resolve(
                "chengyinhub-admin/sql/migration_community_post_platform_20260909.sql")),
                "CREATE TABLE IF NOT EXISTS community_post (");
        String guidelineFk = ",\n  CONSTRAINT fk_community_post_guideline FOREIGN KEY (guideline_version_id)\n"
                + "    REFERENCES community_guideline_version(id) ON DELETE RESTRICT";
        assertEquals(1, occurrences(postDdl, guidelineFk), "community_post 外键形状变了，需复核本取证夹具");
        db.execute(postDdl.replace(guidelineFk, ""));
        insertPost(db, 10L, 100L);
        insertPost(db, 20L, 200L);
        insertPost(db, 30L, 300L);
        insertPost(db, 40L, 400L);
        // 最大年份：两条互不相同的合格事件（不同会员、不同帖子），12-31 取当天最后一秒。
        insertQualifiedView(db, 1L, 10L, "9999-12-30 12:00:00");
        insertQualifiedView(db, 2L, 20L, "9999-12-31 23:59:59");
        // 普通日期对照组。
        insertQualifiedView(db, 3L, 30L, "2026-09-01 12:00:00");
        insertQualifiedView(db, 4L, 40L, "2026-09-02 23:59:59");
        try (InputStream stream = Resources.getResourceAsStream(RESOURCE)) {
            productionXml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        log("resource=" + Resources.getResourceURL(RESOURCE));
        log("engine=" + db.queryForList("SELECT table_name, engine FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() ORDER BY table_name"));
        log("fixtures=" + db.queryForList("SELECT id, member_id, biz_id, occurred_at FROM biz_event_log ORDER BY id"));
    }

    @AfterAll
    void cleanup() {
        if (created) {
            admin.execute("DROP DATABASE " + config.databaseName());
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    Integer.class, config.databaseName()));
        }
    }

    @Test
    void dateAddEndBoundAtMaxYear() throws Exception {
        String statement = selectElement(productionXml);
        assertEquals(1, occurrences(statement, DATE_ADD_LINE), "master 结束边界应仍是 DATE_ADD");
        log("DATE_ADD statement sha256=" + sha256(statement));
        Configuration configuration = configuration();
        configuration.getTypeAliasRegistry().registerAlias("BizFunnelDaily", BizFunnelDaily.class);
        configuration.addMapper(BizFunnelDailyMapper.class);
        try (InputStream stream = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(stream, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession()) {
            BizFunnelDailyMapper mapper = session.getMapper(BizFunnelDailyMapper.class);
            runAndAssert("DATE_ADD", session, mapper::selectCommunityQualifiedPilotMetric);
        }
    }

    @Test
    void timestampAddCandidateEndBoundAtMaxYear() throws Exception {
        String production = selectElement(productionXml);
        String candidate = selectElement(CANDIDATE_528E9486);
        assertEquals(production.replace(DATE_ADD_LINE, TIMESTAMPADD_LINE), candidate,
                "528e9486 候选与现行语句应只差结束边界一行");
        log("TIMESTAMPADD statement sha256=" + sha256(candidate));
        Configuration configuration = configuration();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" "
                + "\"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n"
                + "<mapper namespace=\"" + CANDIDATE_NAMESPACE + "\">\n" + CANDIDATE_528E9486 + "</mapper>\n";
        new XMLMapperBuilder(new StringReader(xml), configuration, "candidate-528e9486",
                configuration.getSqlFragments()).parse();
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession()) {
            runAndAssert("TIMESTAMPADD", session, (start, end) -> session.selectOne(
                    CANDIDATE_NAMESPACE + "." + STATEMENT, Map.of("startDate", start, "endDate", end)));
        }
    }

    private void runAndAssert(String variant, SqlSession session,
                              BiFunction<String, String, Map<String, Object>> query) throws Exception {
        Connection connection = session.getConnection();
        log(variant + " session " + queryRows(connection, "select @@version, @@sql_mode"));
        log(variant + " probe " + queryRows(connection,
                "select date_add(cast('9999-12-31' as date), interval 1 day) as date_add_end, "
                        + "timestampadd(day, 1, cast('9999-12-31' as date)) as timestampadd_end"));
        log(variant + " probe warnings " + queryRows(connection, "SHOW WARNINGS"));
        List<Executable> checks = new ArrayList<>();
        String[][] windows = {
                {"2026-09-01", "2026-09-01", "1"},
                {"2026-09-02", "2026-09-02", "1"},
                {"2026-09-01", "2026-09-02", "2"},
                {"9999-12-30", "9999-12-30", "1"},
                {"9999-12-31", "9999-12-31", "1"},
                {"9999-12-30", "9999-12-31", "2"},
        };
        for (String[] window : windows) {
            session.clearCache();
            Map<String, Object> metric = query.apply(window[0], window[1]);
            // 同一连接上紧跟着取诊断：中间不夹任何其它语句。
            List<Map<String, Object>> warnings = queryRows(connection, "SHOW WARNINGS");
            String label = variant + " " + window[0] + ".." + window[1];
            log(label + " result=" + metric + " warnings=" + warnings);
            long expected = Long.parseLong(window[2]);
            checks.add(() -> assertEquals(expected, number(metric, "qualified_user_count"),
                    label + " qualified_user_count; warnings=" + warnings));
            checks.add(() -> assertEquals(expected, number(metric, "qualified_user_post_count"),
                    label + " qualified_user_post_count; warnings=" + warnings));
        }
        assertAll(variant, checks);
    }

    private Configuration configuration() {
        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("funnel-max-date", new JdbcTransactionFactory(), source));
        return configuration;
    }

    private static List<Map<String, Object>> queryRows(Connection connection, String sql) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(result.getMetaData().getColumnLabel(i), result.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static long number(Map<String, Object> metric, String key) {
        for (Map.Entry<String, Object> entry : metric.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return ((Number) entry.getValue()).longValue();
            }
        }
        throw new AssertionError("missing " + key + " in " + metric);
    }

    private static String selectElement(String xml) {
        String open = "<select id=\"" + STATEMENT + "\"";
        int from = xml.indexOf(open);
        int to = xml.indexOf("</select>", from);
        assertTrue(from >= 0 && to > from, "statement " + STATEMENT + " not found");
        return xml.substring(from, to + "</select>".length());
    }

    private static String ddl(String script, String head) {
        int from = script.indexOf(head);
        int close = script.indexOf("\n) ENGINE", from);
        int end = script.indexOf(';', close);
        assertTrue(from >= 0 && close > from && end > close, "DDL not found: " + head);
        return script.substring(from, end);
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void insertPost(JdbcTemplate db, long id, long authorId) {
        db.update("INSERT INTO community_post(id, author_id, client_request_id, post_type, body, audience, "
                        + "comment_policy, lifecycle, safety_labels_json, guideline_version_id, create_time, update_time) "
                        + "VALUES (?, ?, ?, 'MOMENT', 'fixture', 'PUBLIC', 'EVERYONE', 'PUBLISHED', JSON_ARRAY(), 1, "
                        + "'2026-09-01 00:00:00', '2026-09-01 00:00:00')",
                id, authorId, "funnel-max-date-" + id);
    }

    private static void insertQualifiedView(JdbcTemplate db, long memberId, long postId, String occurredAt) {
        // occurred_at 以字面量写入，避免驱动按 JVM 时区换算；查询边界也只与日期字面量比较。
        db.update("INSERT INTO biz_event_log(event_name, biz_type, biz_id, member_id, occurred_at) "
                + "VALUES ('community_post_qualified_view', 'community_post', ?, ?, CAST(? AS DATETIME))",
                postId, memberId, occurredAt);
    }

    private DriverManagerDataSource source(String url) {
        return new DriverManagerDataSource(url, config.user(), config.password());
    }

    private static void log(String line) {
        System.out.println("[F3-MAXDATE] " + line);
    }
}
