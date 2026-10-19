package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍照一律机审(2026-09-17 拍板)的真库语义:{@code markPhotoMachineApproved} 的条件更新谓词
 * 就是「完成事实成立」的唯一裁判,替身测不了。
 *
 * <p>走真实 {@code PlayerNodeProgressMapper.xml} + 本机 scratch MySQL(随机 UUID 库名,用后 DROP,
 * 并回读 information_schema 确认归 0),不用 H2 模拟。由 {@code CHENGYIN_FUNDS_MYSQL_IT=1} 门禁启用。
 *
 * <p>钉住:
 * <ol>
 *   <li>存量待审行(0,0)通过 0→1 条件更新恰好命中一次(重复恒 0 行)⇒ 发奖不会双发;</li>
 *   <li>驳回态(0,2)走同一 CAS 也能转正(人工审核下线后不存在「必须驳回」的语义);</li>
 *   <li>已完成行(1,*)永远拿不到行 ⇒ 不会重复发奖。</li>
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoReviewProgressMybatisIntegrationTest {

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private PlayerNodeProgressMapper mapper;
    private IPlayerNodeProgressService service;

    @BeforeAll
    void setUp() throws Exception {
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("photo_review");
        assertTrue(config.adminUrl().startsWith("jdbc:mysql://127.0.0.1:")
                        || config.adminUrl().startsWith("jdbc:mysql://localhost:"),
                "只允许本机 scratch MySQL");
        database = "chengyin_photo_review_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source = new DriverManagerDataSource(
                config.databaseUrl().replace("/" + config.databaseName() + "?", "/" + database + "?"),
                config.user(), config.password());
        db = new JdbcTemplate(source);
        // 与 sql/base_schema_20260818.sql 的 player_node_progress 同形,另补迁移新增的两列
        db.execute("CREATE TABLE player_node_progress ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, member_id BIGINT NOT NULL, activity_id BIGINT NOT NULL,"
                + " play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default',"
                + " topic_id BIGINT DEFAULT NULL, node_id BIGINT NOT NULL, status TINYINT DEFAULT '1',"
                + " checkin_time DATETIME DEFAULT NULL, pic_url VARCHAR(255) DEFAULT NULL,"
                + " review_status TINYINT NOT NULL DEFAULT '1', longitude VARCHAR(32) DEFAULT NULL,"
                + " latitude VARCHAR(32) DEFAULT NULL, puzzle_score SMALLINT NULL, completion_mode VARCHAR(16) NULL,"
                + " del_flag INT DEFAULT '0', create_time DATETIME DEFAULT NULL,"
                + " PRIMARY KEY (id),"
                + " UNIQUE KEY uk_member_activity_node (member_id, activity_id, node_id, play_instance_id),"
                + " UNIQUE KEY uk_progress (member_id, topic_id, node_id, activity_id, play_instance_id)"
                + ") ENGINE=InnoDB");
        db.execute("CREATE TABLE topic_route_session ("
                + "id BIGINT NOT NULL AUTO_INCREMENT, activity_id BIGINT NOT NULL DEFAULT 0,"
                + " topic_id BIGINT NOT NULL, member_id BIGINT NOT NULL,"
                + " play_instance_id VARCHAR(64) NOT NULL DEFAULT 'default', PRIMARY KEY (id)"
                + ") ENGINE=InnoDB");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain,com.chengyinhub.business.domain.vo");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"));
        SqlSessionFactory sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(PlayerNodeProgressMapper.class);
        PlayerNodeProgressServiceImpl impl = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(impl, "playerNodeProgressMapper", mapper);
        service = impl;
    }

    @BeforeEach
    void resetTable() {
        // PER_CLASS 共享同一张表:同一 memberId/activityId/nodeId 组合跨用例复用,
        // 不清表会撞 uk_member_activity_node —— 每条用例前清空,互不干扰。
        db.execute("TRUNCATE TABLE player_node_progress");
    }

    @AfterAll
    void tearDown() {
        if (database != null && admin != null) {
            admin.execute("DROP DATABASE " + database);
            Integer left = admin.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                    Integer.class, database);
            assertEquals(0, left, "scratch 库必须已删除干净,回读期望 0,实际:" + left);
        }
    }

    private Long seedPending(String picUrl, Date submittedAt) {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(71L);
        p.setActivityId(72L);
        p.setTopicId(73L);
        p.setNodeId(74L);
        p.setStatus(0);
        p.setCheckinTime(submittedAt);
        p.setPicUrl(picUrl);
        p.setReviewStatus(0);
        p.setDelFlag(0);
        p.setCreateTime(submittedAt);
        assertEquals(1, mapper.insertPlayerNodeProgress(p));
        assertNotNull(p.getId());
        return p.getId();
    }

    private Map<String, Object> row(Long id) {
        return db.queryForMap("SELECT status, review_status, pic_url, checkin_time, del_flag"
                + " FROM player_node_progress WHERE id = " + id);
    }

    /** mysql-connector-j 按驱动版本把 DATETIME 读成 Timestamp 或 LocalDateTime,两种都接。 */
    private static java.time.Instant toInstant(Object checkinTime) {
        if (checkinTime instanceof java.sql.Timestamp) return ((java.sql.Timestamp) checkinTime).toInstant();
        if (checkinTime instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) checkinTime).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        throw new IllegalArgumentException("未预期的 checkin_time 类型:" + checkinTime);
    }

    @Test
    void machineApproveCasHitsExactlyOnceAndWritesCompletionTime() {
        Date submitted = new Date(System.currentTimeMillis() - 60_000L);
        Long id = seedPending("https://oss/upload/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg", submitted);

        assertEquals(1, service.markPhotoMachineApproved(id), "存量待审 0→1 必须命中");
        Map<String, Object> after = row(id);
        assertEquals(1, ((Number) after.get("status")).intValue());
        assertEquals(1, ((Number) after.get("review_status")).intValue());
        assertTrue(toInstant(after.get("checkin_time")).isAfter(submitted.toInstant()),
                "完成时间应改为本次机审通过时刻");

        assertEquals(0, service.markPhotoMachineApproved(id), "重复通过必须恒 0 行(发奖唯一性裁判)");
    }

    @Test
    void legacyRejectedRowIsAlsoApprovedByTheSameCas() {
        Long id = seedPending("https://oss/upload/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.jpg", new Date());
        // 人工审核时代可能留下的驳回态:谓词不再看 review_status,同一 CAS 应转正
        db.execute("UPDATE player_node_progress SET review_status = 2 WHERE id = " + id);

        assertEquals(1, service.markPhotoMachineApproved(id), "驳回态也是存量未完成行,必须能转正");
        Map<String, Object> after = row(id);
        assertEquals(1, ((Number) after.get("status")).intValue());
        assertEquals(1, ((Number) after.get("review_status")).intValue());
    }

    @Test
    void doneRowIsNeverReapproved() {
        Long id = seedPending("https://oss/upload/cccccccccccccccccccccccccccccccc.jpg", new Date());
        assertEquals(1, service.markPhotoMachineApproved(id));

        assertEquals(0, service.markPhotoMachineApproved(id), "已完成行不得再拿到行,防双发奖");
    }

    @Test
    void selectByIdReturnsTheExactRow() {
        Long id = seedPending("https://oss/upload/eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee.jpg", new Date());
        PlayerNodeProgress found = service.selectPlayerNodeProgressById(id);
        assertNotNull(found);
        assertEquals(id, found.getId());
        assertEquals(71L, found.getMemberId());
        assertEquals(73L, found.getTopicId());
        assertEquals(Integer.valueOf(0), found.getReviewStatus());
    }

    @Test
    void duplicatePendingInsertIsBlockedByUniqueKey() {
        seedPending("https://oss/upload/ffffffffffffffffffffffffffffffff.jpg", new Date());
        PlayerNodeProgress dup = new PlayerNodeProgress();
        dup.setMemberId(71L);
        dup.setActivityId(72L);
        dup.setTopicId(73L);
        dup.setNodeId(74L);
        dup.setStatus(0);
        dup.setPicUrl("https://oss/upload/11111111111111111111111111111111.jpg");
        dup.setReviewStatus(0);
        dup.setDelFlag(0);
        assertThrows(DuplicateKeyException.class, () -> mapper.insertPlayerNodeProgress(dup));
    }

    @Test
    void legacyPendingRowIsNotASubmitToCompleteRowForListReaders() {
        seedPending("https://oss/upload/22222222222222222222222222222222.jpg", new Date());
        // member + activity 的跨主题列表必须经运行时选局；只补读侧 fixture，不放宽生产 gate。
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        when(runtime.playInstances(anyList())).thenAnswer(call -> {
            List<TopicRouteSession> scopes = call.getArgument(0);
            assertEquals(1, scopes.size());
            assertEquals(Long.valueOf(71L), scopes.get(0).getMemberId());
            assertEquals(Long.valueOf(73L), scopes.get(0).getTopicId());
            assertEquals(Long.valueOf(72L), scopes.get(0).getActivityId());
            return Collections.singletonMap(ITopicRouteRuntimeService.sessionKey(71L, 73L, 72L), "default");
        });
        PlayerNodeProgressServiceImpl reader = new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(reader, "playerNodeProgressMapper", mapper);
        ReflectionTestUtils.setField(reader, "routeRuntimeService", runtime);
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(71L);
        probe.setActivityId(72L);
        List<PlayerNodeProgress> rows = reader.selectPlayerNodeProgressList(probe);
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getStatus(), "存量待审行存在但 status=0,读侧不得当完成");
        assertEquals(0, rows.get(0).getReviewStatus());
        assertEquals("default", rows.get(0).getPlayInstanceId());
        verify(runtime, times(2)).playInstances(anyList()); // SQL 读取前选局，读取后再次确认未换局。
    }
}
