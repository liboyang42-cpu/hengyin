package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.OrientScoreSettlement;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1b 城市定向计分 SQL:只追加流水的唯一键(本局幂等键 / 每条至多冲正一次)、每局一次投影、
 * 拍照待审任务不算通过、排行榜附加分与 D-13 同口径取最新一局。
 */
class OrientScoringMyBatisTest
{
    private static SqlSessionFactory factory;

    @BeforeAll
    static void setUp() throws Exception
    {
        Configuration configuration = new Configuration();
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:orient_scoring;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/business/OrientScoringMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource))
        {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true);
             Statement st = session.getConnection().createStatement())
        {
            st.execute("create table topic_route_session(id bigint primary key, activity_id bigint, topic_id bigint, "
                    + "member_id bigint, play_instance_id varchar(64), status int, completed_at timestamp, "
                    + "create_time timestamp, update_time timestamp)");
            st.execute("create table player_node_progress(id bigint primary key, member_id bigint, activity_id bigint, "
                    + "topic_id bigint, node_id bigint, status int, review_status int, puzzle_score int, "
                    + "completion_mode varchar(16), checkin_time timestamp, del_flag int, play_instance_id varchar(64))");
            st.execute("create table orient_score_ledger(id bigint auto_increment primary key, session_id bigint not null, "
                    + "activity_id bigint not null, topic_id bigint not null, member_id bigint not null, "
                    + "dimension varchar(32) not null, delta_centi int not null, reason_code varchar(64) not null, "
                    + "source_type varchar(32) not null, source_ref varchar(96), node_id bigint, reverses_entry_id bigint, "
                    + "idempotency_key varchar(96) not null, request_fingerprint char(64) not null, "
                    + "operator_type varchar(16) not null, operator_id bigint, create_time timestamp not null, "
                    + "constraint uk_orient_ledger_idem unique (session_id, idempotency_key), "
                    + "constraint uk_orient_ledger_reverse unique (reverses_entry_id))");
            st.execute("create table orient_score_settlement(id bigint auto_increment primary key, session_id bigint not null, "
                    + "activity_id bigint, topic_id bigint, member_id bigint, total_centi bigint, entry_count int, "
                    + "ledger_watermark_id bigint, points_awarded int, xp_awarded int, points_rate_pct int, xp_rate_pct int, "
                    + "idempotency_key varchar(96), request_fingerprint char(64), create_time timestamp, "
                    + "constraint uk_orient_settlement_session unique (session_id))");
            st.execute("create table orient_score_appeal(id bigint auto_increment primary key, session_id bigint, "
                    + "member_id bigint, entry_id bigint, node_id bigint, reason varchar(500), status int, "
                    + "resolution_entry_id bigint, resolution_note varchar(500), handled_by varchar(64), handled_at timestamp, "
                    + "idempotency_key varchar(96), request_fingerprint char(64), create_time timestamp, update_time timestamp, "
                    + "constraint uk_orient_appeal_idem unique (member_id, idempotency_key))");
            st.execute("create table orient_result_dispute(id bigint auto_increment primary key, session_id bigint, "
                    + "member_id bigint, reason varchar(500), status int, window_deadline timestamp, "
                    + "resolution_note varchar(500), handled_by varchar(64), handled_at timestamp, "
                    + "idempotency_key varchar(96), request_fingerprint char(64), create_time timestamp, update_time timestamp, "
                    + "constraint uk_orient_dispute_idem unique (member_id, idempotency_key))");
            // 会话:1 号本场两局(10 旧、11 新);2 号本场一局;3 号别场
            st.execute("insert into topic_route_session values "
                    + "(10,500,300,1,'default',1,timestamp '2026-10-01 10:30:00',timestamp '2026-10-01 10:00:00',timestamp '2026-10-01 10:30:00'),"
                    + "(11,500,300,1,'replay-2',0,null,timestamp '2026-10-02 10:00:00',timestamp '2026-10-02 10:00:00'),"
                    + "(12,500,300,2,'default',1,timestamp '2026-10-01 11:00:00',timestamp '2026-10-01 10:00:00',timestamp '2026-10-01 11:00:00'),"
                    + "(13,501,300,3,'default',1,timestamp '2026-10-01 11:00:00',timestamp '2026-10-01 10:00:00',timestamp '2026-10-01 11:00:00')");
            // 2 号:节点 20 通过(谜题 80);21 拍照待审(status=1,review_status=0);22 驳回;23 软删;24 未完成;25 别实例
            st.execute("insert into player_node_progress values "
                    + "(1,2,500,300,20,1,1,80,'SOLVED',timestamp '2026-10-01 10:10:00',0,'default'),"
                    + "(2,2,500,300,21,1,0,null,null,timestamp '2026-10-01 10:20:00',0,'default'),"
                    + "(3,2,500,300,22,1,2,null,null,timestamp '2026-10-01 10:25:00',0,'default'),"
                    + "(4,2,500,300,23,1,1,null,null,timestamp '2026-10-01 10:26:00',2,'default'),"
                    + "(5,2,500,300,24,0,1,null,null,null,0,'default'),"
                    + "(6,2,500,300,26,1,1,null,null,timestamp '2026-10-01 10:27:00',0,'other')");
        }
    }

    private static OrientScoreEntry entry(long sessionId, long memberId, int delta, String key, Long reverses)
    {
        OrientScoreEntry e = new OrientScoreEntry();
        e.setSessionId(sessionId);
        e.setActivityId(sessionId == 13 ? 501L : 500L);
        e.setTopicId(300L);
        e.setMemberId(memberId);
        e.setDimension(OrientScoreEntry.DIM_TASK);
        e.setDeltaCenti(delta);
        e.setReasonCode("TASK_CLEARED");
        e.setSourceType("PROGRESS");
        e.setIdempotencyKey(key);
        e.setRequestFingerprint("f".repeat(64));
        e.setOperatorType("SYSTEM");
        e.setReversesEntryId(reverses);
        e.setCreateTime(new Date());
        return e;
    }

    @Test
    void photoUnderReviewOrRejectedTasksAreNotCleared()
    {
        try (SqlSession session = factory.openSession(true))
        {
            List<PlayerNodeProgress> cleared = session.getMapper(OrientScoringMapper.class)
                    .selectClearedProgress(2L, 500L, 300L, "default");
            assertEquals(List.of(20L), cleared.stream().map(PlayerNodeProgress::getNodeId).collect(Collectors.toList()),
                    "只有 status=1 且 review_status=1 的本实例未删行算通过");
            assertEquals(Integer.valueOf(80), cleared.get(0).getPuzzleScore());
        }
    }

    @Test
    void ledgerIsAppendOnlyWithIdempotencyAndSingleReversal()
    {
        try (SqlSession session = factory.openSession(true))
        {
            OrientScoringMapper mapper = session.getMapper(OrientScoringMapper.class);
            OrientScoreEntry first = entry(12, 2, 10000, "task:node:20", null);
            assertEquals(1, mapper.insertEntry(first));
            assertNotNull(first.getId());
            assertThrows(PersistenceException.class, () -> mapper.insertEntry(entry(12, 2, 10000, "task:node:20", null)),
                    "同局同幂等键只能入账一次");
            OrientScoreEntry reversal = entry(12, 2, -10000, "reverse:1", first.getId());
            assertEquals(1, mapper.insertEntry(reversal));
            assertThrows(PersistenceException.class,
                    () -> mapper.insertEntry(entry(12, 2, -10000, "reverse:2", first.getId())),
                    "同一条流水至多被冲正一次");
            assertNotNull(mapper.selectEntryByReverses(first.getId()));
            assertEquals("task:node:20", mapper.selectEntryByIdem(12L, "task:node:20").getIdempotencyKey());
            List<OrientScoreEntry> all = mapper.selectEntriesBySession(12L);
            assertEquals(0, all.stream().mapToInt(OrientScoreEntry::getDeltaCenti).sum(), "冲正后合计归零,原条目仍在");
            assertEquals(2, all.size());
        }
    }

    @Test
    void settlementIsUniquePerSession()
    {
        try (SqlSession session = factory.openSession(true))
        {
            OrientScoringMapper mapper = session.getMapper(OrientScoringMapper.class);
            OrientScoreSettlement s = new OrientScoreSettlement();
            s.setSessionId(10L);
            s.setActivityId(500L);
            s.setTopicId(300L);
            s.setMemberId(1L);
            s.setTotalCenti(0L);
            s.setEntryCount(0);
            s.setLedgerWatermarkId(0L);
            s.setPointsAwarded(0);
            s.setXpAwarded(0);
            s.setPointsRatePct(0);
            s.setXpRatePct(0);
            s.setIdempotencyKey("k1");
            s.setRequestFingerprint("a".repeat(64));
            s.setCreateTime(new Date());
            assertEquals(1, mapper.insertSettlement(s));
            s.setIdempotencyKey("k2");
            assertThrows(PersistenceException.class, () -> mapper.insertSettlement(s), "每局只投影一次");
            assertEquals("k1", mapper.selectSettlementBySession(10L).getIdempotencyKey());
        }
    }

    @Test
    void appealAndDisputeResolveOnlyFromPending()
    {
        try (SqlSession session = factory.openSession(true))
        {
            OrientScoringMapper mapper = session.getMapper(OrientScoringMapper.class);
            OrientScoreAppeal a = new OrientScoreAppeal();
            a.setSessionId(10L);
            a.setMemberId(1L);
            a.setNodeId(99L);
            a.setReason("漏记");
            a.setIdempotencyKey("ap-1");
            a.setRequestFingerprint("b".repeat(64));
            a.setCreateTime(new Date());
            a.setUpdateTime(new Date());
            assertEquals(1, mapper.insertAppeal(a));
            assertEquals(1, mapper.countOpenAppeals(10L, null, 99L));
            assertEquals(0, mapper.countOpenAppeals(10L, null, 98L));
            OrientScoreAppeal resolve = new OrientScoreAppeal();
            resolve.setId(a.getId());
            resolve.setStatus(OrientScoreAppeal.STATUS_REJECTED);
            resolve.setHandledAt(new Date());
            resolve.setUpdateTime(new Date());
            assertEquals(1, mapper.resolveAppeal(resolve));
            assertEquals(0, mapper.resolveAppeal(resolve), "已处理的申诉不能再处理");
            assertEquals(Integer.valueOf(2), mapper.selectAppealByIdem(1L, "ap-1").getStatus());
            assertFalse(mapper.selectAppealsByStatus(2, 10).isEmpty());

            OrientScoreAppeal d = new OrientScoreAppeal();
            d.setSessionId(10L);
            d.setMemberId(1L);
            d.setReason("成绩有误");
            d.setWindowDeadline(new Date(System.currentTimeMillis() + 3600_000L));
            d.setIdempotencyKey("dp-1");
            d.setRequestFingerprint("c".repeat(64));
            d.setCreateTime(new Date());
            d.setUpdateTime(new Date());
            assertEquals(1, mapper.insertDispute(d));
            assertEquals(1, mapper.countOpenDisputes(10L, 1L));
            OrientScoreAppeal read = mapper.selectDisputeByIdem(1L, "dp-1");
            assertNotNull(read.getWindowDeadline());
            assertNull(read.getEntryId());
            resolve.setId(d.getId());
            resolve.setStatus(OrientScoreAppeal.STATUS_ACCEPTED);
            assertEquals(1, mapper.resolveDispute(resolve));
            assertEquals(0, mapper.resolveDispute(resolve));
        }
    }

    @Test
    void boardTotalsUseLatestSessionOfThisBoardOnly()
    {
        try (SqlSession session = factory.openSession(true))
        {
            OrientScoringMapper mapper = session.getMapper(OrientScoringMapper.class);
            mapper.insertEntry(entry(10, 1, 50000, "old-run", null));   // 1 号旧局,不计
            mapper.insertEntry(entry(11, 1, 300, "new-run", null));     // 1 号最新一局
            mapper.insertEntry(entry(13, 3, 99999, "other-board", null)); // 别场
            List<Map<String, Object>> rows = mapper.selectBoardTotals(500L, 300L, List.of(1L, 3L));
            Map<Long, Long> totals = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Object member = row.containsKey("memberid") ? row.get("memberid") : row.get("memberId");
                Object total = row.containsKey("totalcenti") ? row.get("totalcenti") : row.get("totalCenti");
                totals.put(((Number) member).longValue(), ((Number) total).longValue());
            }
            assertEquals(Long.valueOf(300L), totals.get(1L), "只看本场最新一局(max id)");
            assertFalse(totals.containsKey(3L), "别场会话不进本榜");
            assertTrue(Files.exists(Paths.get("sql", "migration_orient_scoring_20261022.sql")));
        }
    }

    @Test
    void migrationIsIdempotentAndSeedsSwitchOff() throws Exception
    {
        String sql = new String(Files.readAllBytes(Paths.get("sql", "migration_orient_scoring_20261022.sql")),
                StandardCharsets.UTF_8);
        String flat = sql.toLowerCase().replaceAll("\\s+", " ");
        for (String table : List.of("orient_score_ledger", "orient_score_settlement", "orient_score_appeal",
                "orient_result_dispute")) {
            assertTrue(flat.contains("create table if not exists " + table + " ("), table + " 必须可重复执行");
        }
        assertTrue(flat.contains("'orient.scoring.enabled', 'false'"), "总开关默认关闭");
        assertTrue(flat.contains("'orient.scoring.points_rate_pct', '0'"), "默认不发积分");
        assertTrue(flat.contains("'orient.scoring.xp_rate_pct', '0'"), "默认不发 XP");
        String executable = sql.replaceAll("(?m)^\\s*--.*$", "").toLowerCase().replaceAll("\\s+", " ");
        assertFalse(executable.matches(".*\\b(update|delete from|drop|alter table)\\b.*"), "只建表与缺失时落种");
        assertTrue(flat.contains("runner: assert=scalar-true"));
    }
}
