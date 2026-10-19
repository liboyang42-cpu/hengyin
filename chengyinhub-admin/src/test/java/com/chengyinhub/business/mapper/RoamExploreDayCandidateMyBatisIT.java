package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.RoamExploreDayCandidate;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 真实 MyBatis + 专用本地 MySQL fixture；不读取或执行仓库 migration。 */
class RoamExploreDayCandidateMyBatisIT {

    private static final String DEFAULT_JDBC_URL =
            com.chengyinhub.business.it.MybatisItDatabase.url("roam_explore_day");
    private static final String JDBC_URL = System.getProperty("chengyin.mybatis.it.jdbcUrl", DEFAULT_JDBC_URL);

    private static final String[] FIXTURE_DDL = {
            "DROP TABLE IF EXISTS audit_task",
            "DROP TABLE IF EXISTS oms_ticket",
            "DROP TABLE IF EXISTS cms_activity",
            "DROP TABLE IF EXISTS mms_merchant",
            "DROP TABLE IF EXISTS cms_topic",
            "CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, member_id BIGINT, product_type INT, lifecycle INT,"
                    + " del_flag INT, selling_started_at DATETIME, sale_end_at DATETIME,"
                    + " roam_referral_enabled INT NOT NULL DEFAULT 0, subtitle VARCHAR(100))",
            "CREATE TABLE mms_merchant (id BIGINT PRIMARY KEY, member_id BIGINT, status BIGINT, del_flag INT)",
            "CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, status BIGINT,"
                    + " publish_status BIGINT, cancel_time DATETIME, del_flag INT, name VARCHAR(100), img_url VARCHAR(255))",
            "CREATE TABLE oms_ticket (id BIGINT PRIMARY KEY, owner_type BIGINT, owner_id BIGINT, del_flag INT,"
                    + " remaining_inventory BIGINT, signup_deadline DATETIME, gather_lat DECIMAL(10,6),"
                    + " gather_lng DECIMAL(10,6), meeting_point VARCHAR(255))",
            "CREATE TABLE audit_task (id BIGINT PRIMARY KEY, biz_type VARCHAR(64), biz_id BIGINT, status INT,"
                    + " snapshot_json TEXT, submitted_at DATETIME)"
    };

    private static final String[] FIXTURE_DML = {
            "INSERT INTO cms_topic VALUES"
                    + " (1,11,2,3,0,'2026-08-15 08:00:00','2026-08-16 20:00:00',0,'本期副标题'),"
                    + " (2,12,2,3,0,'2026-08-15 08:00:00','2026-08-16 20:00:00',1,'框外副标题')",
            "INSERT INTO mms_merchant VALUES (21,11,1,0),(22,12,1,0)",
            "INSERT INTO cms_activity VALUES"
                    + " (101,1,1,1,NULL,0,'合格活动','good.png'),"
                    + " (102,2,1,1,NULL,0,'框外活动','outside.png')",
            "INSERT INTO oms_ticket VALUES"
                    + " (201,2,101,0,5,'2026-08-16 18:00:00',31.230416,121.473701,'人民广场'),"
                    + " (202,2,102,0,5,'2026-08-16 18:00:00',31.260000,121.473701,'框外')",
            "INSERT INTO audit_task VALUES"
                    + " (301,'TOPIC_PROJECT_REVIEW',1,1,'{\"requestedTarget\":\"PRICING\"}','2026-08-15 07:00:00'),"
                    + " (302,'TOPIC_SALE_REVIEW',1,1,'{}','2026-08-15 07:30:00')"
    };

    @Test
    void rejectsAnyNonDedicatedLocalJdbcBeforeDatasourceCreation() {
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://203.0.113.7:3306/chengyinhubdb"));
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://127.0.0.1:3306/chengyinhubdb"));
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://localhost:3306/chengyin_mybatis_it"));
    }

    @Test
    void realMyBatisExecutesCandidateSqlAndPersistsAdminSwitch() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            try {
                executeAll(statement, FIXTURE_DDL);
                executeAll(statement, FIXTURE_DML);

                CmsTopic patch = new CmsTopic();
                patch.setId(1L);
                patch.setRoamReferralEnabled(1);
                CmsTopicMapper topicMapper = session.getMapper(CmsTopicMapper.class);
                assertEquals(1, topicMapper.updateCmsTopic(patch));
                assertEquals(1, topicMapper.selectRoamReferralEnabledById(1L));

                RoamExploreDayCandidateMapper candidateMapper =
                        session.getMapper(RoamExploreDayCandidateMapper.class);
                List<RoamExploreDayCandidate> rows = candidateMapper.selectPotentialCandidates(
                        31.22, 31.24, 121.46, 121.49);

                assertEquals(1, rows.size(), "真实 DECIMAL BETWEEN 必须只命中框内活动壳");
                RoamExploreDayCandidate row = rows.get(0);
                assertEquals(101L, row.getActivityId());
                assertEquals(1, row.getRoamReferralEnabled());
                assertEquals(1, row.getProjectReviewStatus());
                assertEquals(1, row.getSaleReviewStatus());
                assertEquals("人民广场", row.getMeetingPoint());
            } finally {
                for (int i = 4; i >= 0; i--) {
                    statement.execute(FIXTURE_DDL[i]);
                }
            }
        }
    }

    private static SqlSessionFactory factory() throws Exception {
        requireDedicatedLocalFixture(JDBC_URL);
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        configuration.getTypeAliasRegistry().registerAlias("RoamExploreDayCandidate", RoamExploreDayCandidate.class);
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", JDBC_URL,
                System.getProperty("chengyin.mybatis.it.user",
                        com.chengyinhub.business.it.MybatisItDatabase.user()),
                System.getProperty("chengyin.mybatis.it.password",
                        com.chengyinhub.business.it.MybatisItDatabase.password()));
        configuration.setEnvironment(new Environment("roam-candidate-it", new JdbcTransactionFactory(), dataSource));
        parse(configuration, "mapper/business/CmsTopicMapper.xml");
        parse(configuration, "mapper/business/RoamExploreDayCandidateMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    // 2026-08-19 一类一库之后,库名从固定的 chengyin_mybatis_it 变成 chengyin_mybatis_<suite>[后缀]。
    // 守卫跟着改成**前缀**匹配,严格度没降:仍然钉死 127.0.0.1 与 chengyin_mybatis_ 这个家族前缀,
    // 生产地址、别的库名照旧拒绝(本类的负控用例就是拿生产 URL 打这条)。
    // 2026-08-27 端口不再钉死:CI 的 fixture 容器因本机 3306 被常驻 mysqld 占用改发 3307
    //(CHENGYIN_MYBATIS_IT_PORT),守卫的保护面是「只准本机 + 只准专用库家族」,与端口无关 ——
    // 姊妹类 TopicEditionReopen/PendingRecruit 的同名守卫从一开始就是这个口径。
    static void requireDedicatedLocalFixture(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.matches(
                "^jdbc:mysql://127\\.0\\.0\\.1:\\d{1,5}/chengyin_mybatis_[a-z0-9_]+(?:\\?.*)?$")) {
            throw new IllegalStateException("拒绝非专用本地 fixture JDBC:仅允许 127.0.0.1:<port>/chengyin_mybatis_*");
        }
    }

    private static void executeAll(Statement statement, String[] sql) throws Exception {
        for (String item : sql) statement.execute(item);
    }

    private static void parse(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }
}
