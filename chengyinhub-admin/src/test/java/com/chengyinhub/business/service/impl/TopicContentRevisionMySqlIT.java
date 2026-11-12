package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicContentRevision;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWrite;
import com.chengyinhub.business.mapper.CmsTopicContentRevisionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.support.CanonicalDigest;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chengyinhub.business.domain.dto.TopicContentRevisionWriteResult;

/**
 * BE-06 修订索引的真库合同(MySQL,显式开关 CHENGYIN_TOPIC_REVISION_MYSQL_IT=1)。
 *
 * <p>单元测试证明语义,这里只证单元测试证不了的三件事:
 * <ol>
 *   <li>LONGTEXT 原字节回读 —— hash 可以由库里的字节自证,JSON 列达不到;</li>
 *   <li>唯一键真实形状:revision_no / publish_request_id 唯一,(topic_id, content_sha256) 普通索引
 *       —— A→B→恢复A 的第三行 hash 与第一行相同必须能落库;</li>
 *   <li>并发写入不会产生同号修订。</li>
 * </ol>
 *
 * <p>不建 cms_topic 表:{@code 行锁} 由 mock 的 CmsTopicMapper 提供,这里只验证修订索引本身。
 * 迁移脚本即生产待执行的同一份 {@code sql/migration_20261108_topic_content_revision.sql}。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_TOPIC_REVISION_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicContentRevisionMySqlIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("topic_content_revision_it");

    private static final long TOPIC = 9001L;
    private static final long OWNER = 42L;

    private static final String FROZEN_A = "{"
            + "\"runtimeSnapshotVersion\":1,\"routeMode\":\"BRANCH_GRAPH\","
            + "\"routeStations\":[{\"nodeId\":11,\"nodeName\":\"站一\",\"scanReady\":true}],"
            + "\"nodeAnswers\":{\"11\":{\"questionAnswer\":\"旧答案\"}},"
            + "\"nodeConfigs\":{\"11\":{\"kind\":\"qa\"}},"
            + "\"themeConfig\":{\"gameplayRuntime\":{}}}";
    private static final String FROZEN_B = FROZEN_A.replace("旧答案", "新答案");

    private DataSource dataSource;
    private JdbcTemplate db;
    private CmsTopicContentRevisionMapper mapper;
    private CmsTopicMapper topics;
    private TopicContentRevisionServiceImpl service;
    private TransactionTemplate tx;

    @BeforeAll
    void createDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("sql/migration_20261108_topic_content_revision.sql"));
        }
        db = new JdbcTemplate(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        mapper = template.getMapper(CmsTopicContentRevisionMapper.class);
        topics = mock(CmsTopicMapper.class);
        service = new TopicContentRevisionServiceImpl();
        ReflectionTestUtils.setField(service, "revisionMapper", mapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearRows() {
        db.update("delete from cms_topic_content_revision");
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        reset(topics);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
        assertEquals(0, new JdbcTemplate(admin).queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, MYSQL.databaseName()));
    }

    @Test
    void exactFrozenPayloadRoundTripsByteForByteAndTheManifestHasNoSecrets() throws Exception {
        TopicContentRevisionWriteResult result = tx.execute(status ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));
        assertFalse(result.isIdempotentReplay());

        String stored = db.queryForObject(
                "select snapshot_json from cms_topic_content_revision where id=?", String.class, result.getRevision().getId());
        String storedSha = db.queryForObject(
                "select content_sha256 from cms_topic_content_revision where id=?", String.class, result.getRevision().getId());
        String manifest = db.queryForObject(
                "select public_manifest_json from cms_topic_content_revision where id=?", String.class, result.getRevision().getId());

        assertEquals(FROZEN_A, stored, "LONGTEXT 必须原字节保存,不能被 JSON 类型规范化");
        assertEquals(CanonicalDigest.sha256Hex(stored), storedSha, "hash 必须能由库里的字节自证");
        JsonNode manifestTree = JSON.readTree(manifest);
        assertEquals(TOPIC, manifestTree.path("topicId").asLong());
        assertEquals(1L, manifestTree.path("revisionNo").asLong());
        assertEquals(1, manifestTree.path("schemaVersion").asInt());
        assertFalse(manifest.contains("旧答案"));
        assertFalse(manifest.contains("nodeAnswers"));
        assertFalse(manifest.contains("nodeConfigs"));
    }

    @Test
    void aThenBThenRestoreAProducesThreeRowsAndTheThirdHashEqualsTheFirst() {
        tx.execute(status -> service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-a")));
        tx.execute(status -> service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_B, "req-b")));
        tx.execute(status -> service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-restore-a")));

        List<String> shas = db.queryForList(
                "select content_sha256 from cms_topic_content_revision where topic_id=? order by revision_no asc",
                String.class, TOPIC);
        List<Long> numbers = db.queryForList(
                "select revision_no from cms_topic_content_revision where topic_id=? order by revision_no asc",
                Long.class, TOPIC);

        assertEquals(List.of(1L, 2L, 3L), numbers);
        assertEquals(3, shas.size());
        assertEquals(shas.get(0), shas.get(2), "恢复 A 的 hash 与第一次相同是事实,不能因此被唯一键挡住");
        assertEquals(CanonicalDigest.sha256Hex(FROZEN_A), shas.get(2));
    }

    @Test
    void sameRequestIdIsIdempotentForTheSamePayloadAndConflictsOnDifferentPayload() {
        TopicContentRevisionWriteResult first = tx.execute(status ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));
        TopicContentRevisionWriteResult replay = tx.execute(status ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_A, "req-1")));

        assertTrue(replay.isIdempotentReplay());
        assertEquals(first.getRevision().getId(), replay.getRevision().getId());
        assertEquals(1L, count());

        ServiceException conflict = assertThrows(ServiceException.class, () -> tx.execute(status ->
                service.recordPublishedRevision(TopicContentRevisionWrite.of(TOPIC, FROZEN_B, "req-1"))));
        assertEquals(Integer.valueOf(409), conflict.getCode());
        assertEquals(1L, count());
    }

    @Test
    void indexShapeRejectsDuplicateRevisionNoAndRequestIdButAllowsRepeatedContentHash() {
        mapper.insertRevision(row(1L, CanonicalDigest.sha256Hex(FROZEN_A), null));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> mapper.insertRevision(row(1L, CanonicalDigest.sha256Hex(FROZEN_A), null)),
                "UNIQUE(topic_id, revision_no) 必须挡住同号");
        mapper.insertRevision(row(2L, CanonicalDigest.sha256Hex(FROZEN_A), null));
        assertEquals(2L, count(), "同一 hash 出现在不同 revision_no 必须允许(A→B→恢复A)");
        // 多个 NULL publish_request_id 允许(普通旧调用),真实请求号重复另见下一个用例。
    }

    @Test
    void duplicateRequestIdAcrossRevisionNumbersIsRejected() {
        mapper.insertRevision(row(1L, CanonicalDigest.sha256Hex(FROZEN_A), "req-x"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> mapper.insertRevision(row(2L, CanonicalDigest.sha256Hex(FROZEN_B), "req-x")));
        assertEquals(1L, count());
    }

    @Test
    void legacyImportStoresTheRealStoredHeadBytesNotARebuiltSnapshot() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setPublishedConfigVersion(4L);
        String stored = "{ \"runtimeSnapshotVersion\":1,\n \"routeStations\":[] }";
        topic.setPublishedRouteGraphJson(stored);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);

        CmsTopicContentRevision imported = tx.execute(status -> service.importLegacyPublished(TOPIC, OWNER));

        assertEquals(stored, imported.getSnapshotJson());
        assertEquals(CanonicalDigest.sha256Hex(stored), imported.getContentSha256());
        assertEquals(CmsTopicContentRevision.ORIGIN_LEGACY_IMPORTED, imported.getOrigin());
        assertEquals(stored, db.queryForObject(
                "select snapshot_json from cms_topic_content_revision where id=?", String.class, imported.getId()));
    }

    @Test
    void concurrentPublishesNeverShareARevisionNumber() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (String requestId : List.of("req-c1", "req-c2")) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return tx.execute(status -> service.recordPublishedRevision(
                                TopicContentRevisionWrite.of(TOPIC, FROZEN_A, requestId)).getRevision().getId());
                    } catch (RuntimeException lostRace) {
                        // 竞争失败必须如实失败(唯一键/死锁),不得静默覆盖;
                        // 具体是 DuplicateKey 还是死锁由 InnoDB 决定,这里只认「没有半行」这个可观察事实。
                        return null;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Long> result : results) if (result.get(20, TimeUnit.SECONDS) != null) succeeded++;
            List<Long> numbers = db.queryForList(
                    "select revision_no from cms_topic_content_revision where topic_id=? order by revision_no", Long.class, TOPIC);
            assertEquals(succeeded, numbers.size(), "每次成功都必须落库,失败不得留下半行");
            assertEquals(numbers.size(), numbers.stream().distinct().count(), "并发发布不得产生相同 revision_no");
            assertTrue(succeeded >= 1);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private long count() {
        return db.queryForObject("select count(*) from cms_topic_content_revision", Long.class);
    }

    private static CmsTopicContentRevision row(long revisionNo, String sha, String requestId) {
        CmsTopicContentRevision row = new CmsTopicContentRevision();
        row.setTopicId(TOPIC);
        row.setRevisionNo(revisionNo);
        row.setSchemaVersion(1);
        row.setContentSha256(sha);
        row.setSnapshotJson(FROZEN_A);
        row.setPublicManifestJson("{\"manifestVersion\":1}");
        row.setPublishRequestId(requestId);
        row.setRequestFingerprint(sha);
        row.setOrigin(CmsTopicContentRevision.ORIGIN_PUBLISH);
        row.setCreateBy(OWNER);
        row.setCreateTime(new Date());
        return row;
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("topic-content-revision-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.addMapper(CmsTopicContentRevisionMapper.class);
        String resource = "mapper/business/CmsTopicContentRevisionMapper.xml";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
