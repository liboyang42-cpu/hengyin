package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.ContentLandingTranslation;
import com.chengyinhub.business.mapper.ContentLandingMetadataMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 译文审校的真库负例：**「看 A 审 B」窗口与行 CAS 并发**。
 *
 * <p>为什么必须真库：服务层是「先算摘要再比较、然后按 row_version CAS 更新」三步。
 * 单测只能证明"同一线程里改了内容会被拒"，证明不了两个事务并发时
 * 是否会出现「A 拿到旧版本 → B 写入新内容 → A 的 CAS 因为 A 读的版本恰好没被 B 改动而通过」。
 * 这类判定只有真实 InnoDB 的行锁/版本推进能给答案。
 *
 * <p>断言的三条不变量：
 * <ol>
 *   <li>并发同版本批准，只有一个成功（另一个 0 行）；</li>
 *   <li>作者在审校窗口内改稿后，用旧摘要/旧版本提交必须失败；</li>
 *   <li>已批准内容被作者改写后，行状态回到 PENDING（不得保持 APPROVED）。</li>
 * </ol>
 *
 * <p>一类一库，跑完 DROP 并回读 0。
 */
class ContentLandingReviewConcurrencyMySqlIT {

    private static final String SUITE = "content_landing_review";
    private static final long TOPIC_ID = 42L;

    @Test
    void concurrentApprovalAndAuthorEditWindow() throws Exception {
        String url = MybatisItDatabase.url(SUITE);
        createSchemaAndSeed(url);

        SqlSessionFactory factory = sessionFactory(url);

        // 1) 并发同版本批准：只有一个能推进版本。
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try (SqlSession session = factory.openSession(false)) {
                    start.await();
                    ContentLandingMetadataMapper mapper = session.getMapper(ContentLandingMetadataMapper.class);
                    ContentLandingTranslation row = mapper.selectTranslation(TOPIC_ID, "en");
                    ContentLandingTranslation update = copyOf(row, "APPROVED");
                    int updated = mapper.updateTranslationVersioned(update, row.getRowVersion(), new Date());
                    session.commit();
                    if (updated == 1) {
                        successes.incrementAndGet();
                    } else {
                        conflicts.incrementAndGet();
                    }
                } catch (RuntimeException failure) {
                    synchronized (errors) {
                        errors.add(String.valueOf(failure.getMessage()));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发用例必须收敛，不许无限等待");
        pool.shutdownNow();

        assertTrue(errors.isEmpty(), "并发提交不得抛异常，实际：" + errors);
        assertEquals(1, successes.get(), "同版本并发批准只能成功一次");
        assertEquals(threads - 1, conflicts.get(), "对手方必须拿到 0 行（版本过期）而不是也写成功");

        // 2) 作者在审校窗口内改稿：旧版本提交必须失败。
        try (SqlSession session = factory.openSession(false)) {
            ContentLandingMetadataMapper mapper = session.getMapper(ContentLandingMetadataMapper.class);
            ContentLandingTranslation current = mapper.selectTranslation(TOPIC_ID, "en");
            long seenVersion = current.getRowVersion();

            ContentLandingTranslation authorEdit = copyOf(current, "PENDING");
            authorEdit.setTitle("作者改过的标题");
            assertEquals(1, mapper.updateTranslationVersioned(authorEdit, seenVersion, new Date()));

            ContentLandingTranslation staleApproval = copyOf(copyOf(current, "APPROVED"), "APPROVED");
            int staleUpdate = mapper.updateTranslationVersioned(staleApproval, seenVersion, new Date());
            assertEquals(0, staleUpdate, "作者改稿后，按旧版本批准必须 0 行");
            session.commit();
        }

        // 3) 作者改已批准内容 → 状态必须回到 PENDING（author saveTranslation 恒写 PENDING）。
        try (SqlSession session = factory.openSession(false)) {
            ContentLandingMetadataMapper mapper = session.getMapper(ContentLandingMetadataMapper.class);
            ContentLandingTranslation current = mapper.selectTranslation(TOPIC_ID, "en");
            assertEquals("PENDING", current.getTranslationState(),
                    "作者改稿后必须是 PENDING，不能保留 APPROVED");
            session.commit();
        }

        // 库由 @AfterAll 整个删除并独立回读；这里不保留表级清理（否则 schema 会残留成 LEFTOVER）。
    }

    // ------------------------------------------------------------------ helpers

    private ContentLandingTranslation copyOf(ContentLandingTranslation source, String state) {
        ContentLandingTranslation copy = new ContentLandingTranslation();
        copy.setTopicId(source.getTopicId());
        copy.setLocale(source.getLocale());
        copy.setSourceLocale(source.getSourceLocale());
        copy.setTitle(source.getTitle());
        copy.setSubtitle(source.getSubtitle());
        copy.setSummary(source.getSummary());
        copy.setTranslationState(state);
        copy.setUpdateBy(7L);
        copy.setUpdateTime(new Date());
        return copy;
    }

    private SqlSessionFactory sessionFactory(String url) throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration configuration = new Configuration(
                new Environment("it", new JdbcTransactionFactory(), dataSource));
        // 生产同一份 mapper：走 classpath。surefire 的工作目录是模块目录 chengyinhub-admin，
        // 用 "chengyinhub-system/..." 相对路径会解析到 chengyinhub-admin/chengyinhub-system 之下而找不到文件。
        String xml = "mapper/business/ContentLandingMetadataMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(xml)) {
            new XMLMapperBuilder(in, configuration, xml, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchemaAndSeed(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS content_landing_metadata ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, topic_id BIGINT NOT NULL,"
                    + "source_locale VARCHAR(16) NULL, landing_summary VARCHAR(500) NULL,"
                    + "group_eligible TINYINT NULL, school_eligible TINYINT NULL,"
                    + "group_min_people INT NULL, group_max_people INT NULL,"
                    + "age_min INT NULL, age_max INT NULL, accessibility_note VARCHAR(255) NULL,"
                    + "status TINYINT NOT NULL DEFAULT 1, row_version BIGINT NOT NULL DEFAULT 1,"
                    + "update_by BIGINT NULL, create_time DATETIME NULL, update_time DATETIME NULL,"
                    + "PRIMARY KEY (id), UNIQUE KEY uk_content_landing_metadata_topic (topic_id))");
            st.execute("CREATE TABLE IF NOT EXISTS content_landing_translation ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT, topic_id BIGINT NOT NULL,"
                    + "locale VARCHAR(16) NOT NULL, source_locale VARCHAR(16) NULL,"
                    + "title VARCHAR(200) NULL, subtitle VARCHAR(255) NULL, summary VARCHAR(500) NULL,"
                    + "translation_state VARCHAR(16) NOT NULL, row_version BIGINT NOT NULL DEFAULT 1,"
                    + "update_by BIGINT NULL, create_time DATETIME NULL, update_time DATETIME NULL,"
                    + "PRIMARY KEY (id),"
                    + "UNIQUE KEY uk_content_landing_translation_topic_locale (topic_id, locale))");
            st.execute("INSERT INTO content_landing_translation"
                    + " (topic_id, locale, source_locale, title, subtitle, summary,"
                    + "  translation_state, row_version, update_by, create_time, update_time)"
                    + " VALUES (" + TOPIC_ID + ", 'en', 'zh-cn', 'Ancient Town Walk', 'Two hours',"
                    + "  'A short walk through the old town.', 'PENDING', 1, 900, NOW(), NOW())");
        }
    }

    /**
     * 一类一库：跑完把**测试自己的** scratch 库删掉，并用独立连接回读 information_schema。
     * 只 DROP TABLE 会留下 schema，真库门禁的清理检查会把它记成 LEFTOVER 并判红。
     */
    @AfterAll
    static void dropScratchDatabase() throws Exception {
        String database = MybatisItDatabase.databaseName(SUITE);
        assertTrue(database.startsWith("chengyin_mybatis_"),
                "只许删专用 fixture 库，实际=" + database);
        String adminUrl = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                + "/?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection admin = DriverManager.getConnection(adminUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
            try (var rows = st.executeQuery("SELECT COUNT(*) FROM information_schema.schemata"
                    + " WHERE schema_name = '" + database + "'")) {
                rows.next();
                assertEquals(0, rows.getInt(1), "IT 库必须已删除并回读为 0:" + database);
            }
        }
    }
}
