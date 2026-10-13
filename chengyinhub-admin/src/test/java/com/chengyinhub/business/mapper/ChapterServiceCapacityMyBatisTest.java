package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
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
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真 MyBatis + H2(MySQL mode)验证一章一行、完整三元组与原子 upsert；不冒充真实 MySQL 证据。 */
class ChapterServiceCapacityMyBatisTest
{
    private static final String SERVICE_CAPACITY_CHECK = "((batch_size is null "
            + "and batch_interval_minutes is null) or (batch_size is not null and batch_size > 0 "
            + "and batch_interval_minutes is not null and batch_interval_minutes > 0 "
            + "and effort_max_concurrent is not null and effort_max_concurrent > 0))";

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:chapter_capacity_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/CmsTopicTemplateMetaMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table cms_topic_chapter_template_meta ("
                    + "id bigint auto_increment primary key, chapter_id bigint not null, topic_id bigint not null,"
                    + "role_need text, exclusivity_mode varchar(8), exclusivity_scope varchar(255),"
                    + "exclusivity_period varchar(255), exclusivity_reason text, effort_prep_hours decimal(6,2),"
                    + "effort_staff_minutes int, effort_venue varchar(255), effort_materials text,"
                    + "batch_size int, batch_interval_minutes int, effort_max_concurrent int,"
                    + "effort_available_slots varchar(255), effort_needs_booking tinyint, effort_cancel_cost text,"
                    + "effort_qualifications text, del_flag tinyint not null default 0,"
                    + "create_time timestamp default current_timestamp, update_time timestamp default current_timestamp,"
                    + "constraint uk_chapter_meta_chapter unique(chapter_id),"
                    + "constraint ck_chapter_meta_service_capacity check " + SERVICE_CAPACITY_CHECK + ")");
        }
    }

    @Test
    void migrationAndH2SchemaUseTheSameExplicitNullCheck() throws Exception
    {
        Path migration = Paths.get("sql/migration_shop_day_batch_capacity_20260815.sql");
        if (!Files.exists(migration)) {
            migration = Paths.get("chengyinhub-admin/sql/migration_shop_day_batch_capacity_20260815.sql");
        }
        String sql = new String(Files.readAllBytes(migration), StandardCharsets.UTF_8);
        String expected = "add constraint ck_chapter_meta_service_capacity check "
                + normalizeSql(SERVICE_CAPACITY_CHECK);

        assertTrue(normalizeSql(sql).contains(expected),
                "migration 与 H2 必须共用显式 IS NOT NULL 的同义 CHECK");
    }

    @Test
    void checkConstraintRejectsPartialNullZeroAndNegativeConfiguredTuples() throws Exception
    {
        assertRawTupleAccepted(null, null, null);
        assertRawTupleAccepted(null, null, 2);
        assertRawTupleAccepted(4, 15, 2);

        assertRawTupleRejected(4, null, 2);
        assertRawTupleRejected(null, 15, 2);
        assertRawTupleRejected(4, 15, null);
        assertRawTupleRejected(0, 15, 2);
        assertRawTupleRejected(-1, 15, 2);
        assertRawTupleRejected(4, 0, 2);
        assertRawTupleRejected(4, -1, 2);
        assertRawTupleRejected(4, 15, 0);
        assertRawTupleRejected(4, 15, -1);
    }

    @Test
    void firstInsertThenUpdateKeepsOneCompleteRow() throws Exception
    {
        upsert(18L, 81L, 4, 15, 2);
        upsert(18L, 81L, 6, 15, 2);

        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "select count(*) from cms_topic_chapter_template_meta where chapter_id=18")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
            CmsTopicChapterTemplateMeta result = session.getMapper(CmsTopicTemplateMetaMapper.class)
                    .selectChapterMetaByChapterId(18L);
            assertEquals(Integer.valueOf(6), result.getBatchSize());
            assertEquals(Integer.valueOf(15), result.getBatchIntervalMinutes());
            assertEquals(Integer.valueOf(2), result.getEffortMaxConcurrent());
        }
    }

    @Test
    void changingOneValueDoesNotClearTheOtherTwo()
    {
        upsert(18L, 81L, 4, 15, 2);
        upsert(18L, 81L, 5, 15, 2);

        CmsTopicChapterTemplateMeta result = read(18L);
        assertEquals(Integer.valueOf(5), result.getBatchSize());
        assertEquals(Integer.valueOf(15), result.getBatchIntervalMinutes());
        assertEquals(Integer.valueOf(2), result.getEffortMaxConcurrent());
    }

    @Test
    @Timeout(10)
    void concurrentUpsertsKeepOneWholeTuple() throws Exception
    {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> { start.await(); return upsert(18L, 81L, 4, 15, 2); }));
        futures.add(executor.submit(() -> { start.await(); return upsert(18L, 81L, 6, 20, 3); }));
        start.countDown();
        try {
            assertTrue(futures.get(0).get() > 0);
            assertTrue(futures.get(1).get() > 0);
        } finally {
            executor.shutdownNow();
        }

        CmsTopicChapterTemplateMeta result = read(18L);
        boolean firstTuple = Integer.valueOf(4).equals(result.getBatchSize())
                && Integer.valueOf(15).equals(result.getBatchIntervalMinutes())
                && Integer.valueOf(2).equals(result.getEffortMaxConcurrent());
        boolean secondTuple = Integer.valueOf(6).equals(result.getBatchSize())
                && Integer.valueOf(20).equals(result.getBatchIntervalMinutes())
                && Integer.valueOf(3).equals(result.getEffortMaxConcurrent());
        assertTrue(firstTuple || secondTuple, "并发结果必须是任一完整三元组，不能列间拼接");
        try (SqlSession session = factory.openSession(true);
             Statement statement = session.getConnection().createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "select count(*) from cms_topic_chapter_template_meta where chapter_id=18")) {
            rows.next();
            assertEquals(1, rows.getInt(1), "唯一键 + 原子 upsert 必须始终保持一章一行");
        }
    }

    private int upsert(Long chapterId, Long topicId, int batchSize, int interval, int concurrent)
    {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(CmsTopicTemplateMetaMapper.class)
                    .upsertChapterServiceCapacity(chapterId, topicId, batchSize, interval, concurrent);
        }
    }

    private CmsTopicChapterTemplateMeta read(Long chapterId)
    {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(CmsTopicTemplateMetaMapper.class).selectChapterMetaByChapterId(chapterId);
        }
    }

    private void assertRawTupleAccepted(Integer batchSize, Integer interval, Integer concurrent) throws Exception
    {
        assertEquals(1, insertRawTuple(batchSize, interval, concurrent));
    }

    private void assertRawTupleRejected(Integer batchSize, Integer interval, Integer concurrent)
    {
        assertThrows(java.sql.SQLException.class, () -> insertRawTuple(batchSize, interval, concurrent));
    }

    private int insertRawTuple(Integer batchSize, Integer interval, Integer concurrent) throws Exception
    {
        try (SqlSession session = factory.openSession(true);
             java.sql.PreparedStatement statement = session.getConnection().prepareStatement(
                     "insert into cms_topic_chapter_template_meta "
                             + "(chapter_id, topic_id, batch_size, batch_interval_minutes, "
                             + "effort_max_concurrent) values (?, 81, ?, ?, ?)")) {
            statement.setLong(1, System.nanoTime());
            statement.setObject(2, batchSize);
            statement.setObject(3, interval);
            statement.setObject(4, concurrent);
            return statement.executeUpdate();
        }
    }

    private static String normalizeSql(String sql)
    {
        return sql.toLowerCase().replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(").replaceAll("\\s+\\)", ")").trim();
    }
}
