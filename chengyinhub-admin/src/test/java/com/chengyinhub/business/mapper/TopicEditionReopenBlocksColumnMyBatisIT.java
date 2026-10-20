package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapter;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 期次重开链路读写章节内容块的列名,必须与 {@code cms_topic_chapter} 的真实列名一致。
 *
 * <p><b>为什么必须是真库</b>:{@code TopicEditionReopenMapper.xml} 里写的是 {@code blocks_json},
 * 而这张表的真实列名是 {@code blocks}
 * （{@code CmsTopicChapterMapper.xml} 的 resultMap 即 {@code column="blocks"}；
 * 2026-08-18 生产快照 {@code sql/base_schema_20260818.sql} 里也是 {@code blocks}）。
 * 列名 typo 是**纯 SQL 层**的错:Java 编译得过、Mockito 测得过、审 diff 也看不出来,
 * 只有把语句真发给 MySQL 才会以 {@code 1054 Unknown column} 现形。
 *
 * <p><b>影响面</b>:{@code selectSourceChapters}（重开时读源期章节)与
 * {@code updateReopenedChapterBlocks}（把 blocks 回填到新期章节)各一处,
 * 加上 resultMap 的映射列。三处都在期次重开这条链路上 ⇒ 该链路一跑就炸。
 *
 * <p>库不可达即<b>红</b>,不 skip —— skip 在构建里算绿。
 */
class TopicEditionReopenBlocksColumnMyBatisIT {

    private static final String DEFAULT_JDBC_URL =
            com.chengyinhub.business.it.MybatisItDatabase.url("topic_edition_reopen");
    private static final String JDBC_URL = System.getProperty("chengyin.mybatis.it.jdbcUrl", DEFAULT_JDBC_URL);

    private static final long SOURCE_TOPIC = 970001L;
    private static final long CHAPTER = 970101L;

    /** 列形状取自生产快照 base_schema_20260818.sql 的 cms_topic_chapter（只保留本链路读写的列）。 */
    private static final String[] FIXTURE_DDL = {
            "DROP TABLE IF EXISTS cms_topic_chapter",
            "CREATE TABLE cms_topic_chapter (id BIGINT PRIMARY KEY, topic_id BIGINT, name VARCHAR(100),"
                    + " description TEXT, blocks LONGTEXT, img_arr TEXT, total_time BIGINT, total_mileage DECIMAL(10,2),"
                    + " location_count BIGINT, template_count BIGINT, sort_id BIGINT, category VARCHAR(64),"
                    + " category_id INT, recruit_enabled TINYINT, perk_min_value DECIMAL(10,2), max_merchant INT,"
                    + " required TINYINT, terms_mode VARCHAR(16), max_per_head_fee DECIMAL(10,2),"
                    + " allowed_validation_methods VARCHAR(64), max_node_xp INT)"
    };

    @Test
    void reopenChapterBlocksUseTheRealColumnName() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                for (String ddl : FIXTURE_DDL) st.execute(ddl);
                st.execute("INSERT INTO cms_topic_chapter (id, topic_id, name, description, blocks, sort_id)"
                        + " VALUES (" + CHAPTER + ", " + SOURCE_TOPIC + ", '第1章', '本章说明', '[{\"type\":\"text\"}]', 1)");
            }
            TopicEditionReopenMapper mapper = session.getMapper(TopicEditionReopenMapper.class);

            // ① 读源期章节:列名错就 1054,连带 resultMap 的 blocksJson 也映射不上
            List<CmsTopicChapter> chapters = mapper.selectSourceChapters(SOURCE_TOPIC);
            assertEquals(1, chapters.size(), "源期章节应读得出来");
            assertNotNull(chapters.get(0).getBlocksJson(), "blocks 列必须映射进 blocksJson,否则重开会把内容块整段丢掉");
            assertEquals("[{\"type\":\"text\"}]", chapters.get(0).getBlocksJson());

            // ② 回填新期章节的 blocks
            assertEquals(1, mapper.updateReopenedChapterBlocks(CHAPTER, "[{\"type\":\"node\"}]"));
            assertEquals("[{\"type\":\"node\"}]", readBlocks(session),
                    "回填必须真的落到 blocks 列上");
        }
    }

    /**
     * 负控:同一张真表上,故意用错列名 {@code blocks_json} 必须当场 1054。
     * 没有这一条,上面那两句「绿了」也可能只是因为 MySQL 宽容,而不是因为列名对。
     */
    @Test
    void wrongColumnNameFailsLoudlyOnTheSameTable() throws Exception {
        SqlSessionFactory factory = factory();
        try (SqlSession session = factory.openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                for (String ddl : FIXTURE_DDL) st.execute(ddl);
            }
            Exception e = assertThrows(Exception.class, () -> {
                try (Statement st = session.getConnection().createStatement()) {
                    st.executeQuery("select blocks_json from cms_topic_chapter");
                }
            });
            String message = String.valueOf(e.getMessage());
            org.junit.jupiter.api.Assertions.assertTrue(
                    message.contains("Unknown column") && message.contains("blocks_json"),
                    "负控要点名是哪一列不存在,实得:" + message);
        }
    }

    private static String readBlocks(SqlSession session) throws Exception {
        try (Statement st = session.getConnection().createStatement();
             ResultSet rs = st.executeQuery("select blocks from cms_topic_chapter where id=" + CHAPTER)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private SqlSessionFactory factory() {
        try {
            requireDedicatedLocalFixture(JDBC_URL);
            Configuration cfg = new Configuration();
            cfg.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
            cfg.addMapper(TopicEditionReopenMapper.class);
            PooledDataSource ds = new PooledDataSource("com.mysql.cj.jdbc.Driver", JDBC_URL, com.chengyinhub.business.it.MybatisItDatabase.user(), com.chengyinhub.business.it.MybatisItDatabase.password());
            cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), ds));
            try (InputStream in = Resources.getResourceAsStream("mapper/business/TopicEditionReopenMapper.xml")) {
                new XMLMapperBuilder(in, cfg, "mapper/business/TopicEditionReopenMapper.xml",
                        cfg.getSqlFragments()).parse();
            }
            SqlSessionFactory f = new SqlSessionFactoryBuilder().build(cfg);
            try (SqlSession probe = f.openSession()) {
                probe.getConnection().isValid(2);
            }
            return f;
        } catch (Throwable t) {
            throw new IllegalStateException("IT 前置不满足(库不可达 / Mapper XML / schema 配置有误),"
                    + "本套必须真跑不得跳过:" + t.getMessage(), t);
        }
    }

    /** 只许连本机专用 fixture 库;生产地址与生产库名一律拒绝在建连接之前。 */
    static void requireDedicatedLocalFixture(String jdbcUrl) {
        // 2026-08-19 一类一库之后库名是 chengyin_mybatis_<suite>[后缀];守卫改成认**家族前缀**,
        // 严格度不降(仍钉死 127.0.0.1 与 chengyin_mybatis_ 前缀,生产地址照旧拒绝 —— 本类负控在打这条)。
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://127.0.0.1:")
                || !jdbcUrl.contains("/chengyin_mybatis_")) {
            throw new IllegalStateException("只允许连本机专用 fixture 库 chengyin_mybatis_*: " + jdbcUrl);
        }
    }

    @Test
    void rejectsAnyNonDedicatedLocalJdbcBeforeDatasourceCreation() {
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://203.0.113.7:3306/chengyinhubdb"));
        assertThrows(IllegalStateException.class,
                () -> requireDedicatedLocalFixture("jdbc:mysql://127.0.0.1:3306/chengyinhubdb"));
    }
}
