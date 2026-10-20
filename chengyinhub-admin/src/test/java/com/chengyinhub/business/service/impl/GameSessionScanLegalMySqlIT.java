package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static com.chengyinhub.business.service.impl.GameSessionLedgerMySqlFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SC-SCAN-LEGAL [P0] 真库验收:玩家提交 → 商家 APPROVE(真服务、真 mapper、真事务)。
 *
 * <p>"玩家进度"在游戏局里是 {@code game_player_node_outcome}:小程序读 {@code /api/game/session/view}
 * 的 {@code player.nodes[].personalState / completionStatus},由 {@code playerView} 从该表投影;
 * {@code player_node_progress} 在游戏局里只当"已到店"判定,核验路径不写它。
 * 这里把 V5b 给出的三条只读 SQL 原样跑在真库上,再和玩家投影、商家站点复盘、订单/台账逐一对账。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameSessionScanLegalMySqlIT {

    final GameSessionLedgerMySqlFixture it = new GameSessionLedgerMySqlFixture();

    @BeforeAll void open() throws Exception { it.open("scan_legal"); }
    @AfterAll void close() { it.close(); }

    @BeforeEach void seed() {
        it.seed();
        // 已售订单的合作单条款 + 本站商家的生效供给(库存 quota_used=0),用来证明核验不碰订单与库存。
        it.db.update("insert into coop_order(id,topic_id,from_id,to_id,to_type,share_mode,status,create_time,del_flag)"
                + " values(701,?,?,?,'merchant',0,0,now(),0)", TOPIC, DIRECTOR, ARCHIVE_MERCHANT);
        it.db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,status)"
                + " values(801,?,1,?,'TRAFFIC',20,0,1)", TOPIC, ARCHIVE_MERCHANT);
        it.arrive(PLAYER_A, ARCHIVE_NODE);
    }

    /** V5b SQL (1):每条 APPROVED 提交恰好一条结果,同一站点运行、COMPLETED、MERCHANT_APPROVAL。 */
    List<Map<String, Object>> approvedJoin() {
        return it.db.queryForList("SELECT s.id,s.status,s.station_run_id AS s_run,o.station_run_id AS o_run,o.node_id,"
                + "o.status AS o_status,o.completion_source FROM game_task_submission s JOIN game_player_node_outcome o"
                + " ON o.source_submission_id=s.id WHERE s.game_session_id=? AND s.status='APPROVED'", SESSION);
    }

    /** V5b SQL (3)。 */
    int fallbackCompleted(long member) {
        return it.db.queryForObject("SELECT COUNT(*) FROM game_player_node_outcome WHERE game_session_id=? AND member_id=?"
                + " AND status='FALLBACK_COMPLETED'", Integer.class, SESSION, member);
    }

    @SuppressWarnings("unchecked")
    Map<Long, Map<String, Object>> playerNodes(long member) {
        GameSessionView view = it.runtime.view(GameActorContext.player(member), ACTIVITY);
        Map<Long, Map<String, Object>> nodes = new java.util.LinkedHashMap<>();
        for (Map<String, Object> node : (List<Map<String, Object>>) view.getPlayer().get("nodes")) {
            nodes.put(((Number) node.get("nodeId")).longValue(), node);
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> merchantStation(long merchant, long stationRunId) {
        GameSessionView view = it.runtime.view(GameActorContext.merchant(merchant), ACTIVITY);
        for (Map<String, Object> station : (List<Map<String, Object>>) view.getMerchant().get("stations")) {
            if (((Number) station.get("stationId")).longValue() == stationRunId) return station;
        }
        throw new AssertionError("merchant view lacks station " + stationRunId);
    }

    GameSessionCommand approveCommand;

    long approveArchiveSubmission() {
        GameCommandReceiptView submitted = it.submit(PLAYER_A, ARCHIVE_NODE, "ARCHIVE_TEXT", "1998");
        assertEquals("APPLIED", submitted.getOutcome());
        long submissionId = it.pendingSubmission(PLAYER_A, ARCHIVE_NODE);
        assertEquals("AWAITING_VERIFICATION", playerNodes(PLAYER_A).get(ARCHIVE_NODE).get("personalState"));
        assertEquals(1, ((Number) merchantStation(ARCHIVE_MERCHANT, ARCHIVE_RUN).get("pendingVerificationCount")).intValue());
        approveCommand = it.verify("archive-approve", ARCHIVE_NODE, submissionId, "APPROVE");
        GameCommandReceiptView approved = it.runtime.command(GameActorContext.merchant(ARCHIVE_MERCHANT), approveCommand);
        assertEquals("APPLIED", approved.getOutcome());
        assertEquals("APPROVED", approved.getResult().get("decision"));
        assertEquals("MERCHANT_APPROVAL", approved.getResult().get("completionSource"));
        return submissionId;
    }

    @Test
    void approvalCompletesOnlyThisStationAndPlayerProjectionOrderAndReceptionAgree() {
        // 旧集市暂停,绑定到旧档案馆的兜底计划版本 2 —— 配置里只有版本 1 获批,所以这是未获批绑定。
        it.db.update("update game_station_run set fallback_node_id=?,fallback_plan_code='MARKET_TO_ARCHIVE',"
                + "fallback_plan_version=2 where id=?", ARCHIVE_NODE, MARKET_RUN);
        Map<String, List<Map<String, Object>>> ledgersBefore = it.snapshot(LEDGERS);
        int revisionBefore = it.revision();

        long submissionId = approveArchiveSubmission();

        // (1) 恰好一条 COMPLETED / MERCHANT_APPROVAL,且与提交同一站点运行。
        List<Map<String, Object>> join = approvedJoin();
        assertEquals(1, join.size(), "readback: " + join);
        Map<String, Object> row = join.get(0);
        assertEquals(submissionId, ((Number) row.get("id")).longValue());
        assertEquals(ARCHIVE_RUN, ((Number) row.get("s_run")).longValue());
        assertEquals(ARCHIVE_RUN, ((Number) row.get("o_run")).longValue());
        assertEquals(ARCHIVE_NODE, ((Number) row.get("node_id")).longValue());
        assertEquals("COMPLETED", row.get("o_status"));
        assertEquals("MERCHANT_APPROVAL", row.get("completion_source"));
        assertEquals(ARCHIVE_MERCHANT, it.db.queryForObject("select decided_by from game_task_submission where id=?",
                Long.class, submissionId));
        // 本局全部结果就这一条:旧钟楼没完成,未获批绑定的旧集市没被顺带完成。
        assertEquals(List.of(Map.of("node_id", ARCHIVE_NODE, "status", "COMPLETED")),
                it.db.queryForList("select node_id,status from game_player_node_outcome where game_session_id=?", SESSION));
        assertEquals(0, fallbackCompleted(PLAYER_A), "SQL (3)");
        long outcomeId = it.db.queryForObject("select id from game_player_node_outcome where game_session_id=?", Long.class, SESSION);
        long receiptId = it.db.queryForObject("select id from game_command_receipt where request_id='archive-approve'"
                + " and actor_member_id=? and actor_perspective='MERCHANT' and action='VERIFY_SUBMISSION' and outcome='APPLIED'",
                Long.class, ARCHIVE_MERCHANT);
        assertEquals(receiptId, it.db.queryForObject("select source_receipt_id from game_player_node_outcome where id=?",
                Long.class, outcomeId));
        assertEquals(revisionBefore + 2, it.revision(), "submit + verify each bump the session once");

        // (2) player_node_progress:到店行恰好一条 status=1,核验前后整表不变(核验不写它)。
        assertEquals(List.of(1), it.db.queryForList("SELECT status FROM player_node_progress WHERE activity_id=?"
                + " AND member_id=? AND node_id=?", Integer.class, ACTIVITY, PLAYER_A, ARCHIVE_NODE));
        // 订单、合作单、团核销台账、库存、权益、奖励:核验前后逐行相等。
        assertEquals(ledgersBefore, it.snapshot(LEDGERS));
        assertEquals(0, it.db.queryForObject("select count(*) from coop_group_verify", Integer.class));

        // 小程序读的玩家投影:本站已完成,来源/顺序与结果行一致;下一站可去,未完成。
        Map<Long, Map<String, Object>> nodes = playerNodes(PLAYER_A);
        Map<String, Object> archive = nodes.get(ARCHIVE_NODE);
        assertEquals("COMPLETED", archive.get("personalState"));
        assertEquals("COMPLETED", archive.get("completionStatus"));
        assertEquals("MERCHANT_APPROVAL", archive.get("completionSource"));
        assertEquals(outcomeId, ((Number) archive.get("completionOrder")).longValue());
        Map<String, Object> clock = nodes.get(CLOCK_NODE);
        assertEquals("ACTIVE", clock.get("status"));
        assertEquals("AVAILABLE", clock.get("personalState"));
        assertNull(clock.get("completionStatus"));
        Map<String, Object> market = nodes.get(MARKET_NODE);
        assertEquals("PAUSED", market.get("personalState"));
        assertNull(market.get("completionStatus"));
        assertNull(market.get("fallback"), "unapproved binding must not be offered to the player either");

        // 商家接待记录(站点复盘)与结果行一致。
        @SuppressWarnings("unchecked")
        Map<String, Object> recap = (Map<String, Object>) merchantStation(ARCHIVE_MERCHANT, ARCHIVE_RUN).get("recap");
        assertEquals(1, ((Number) recap.get("arrivedPlayers")).intValue(), "recap " + recap);
        assertEquals(1, ((Number) recap.get("submissionCount")).intValue());
        assertEquals(1, ((Number) recap.get("approvedCount")).intValue());
        assertEquals(1, ((Number) recap.get("normalCompletedCount")).intValue());
        assertEquals(0, ((Number) recap.get("fallbackCompletedCount")).intValue());
        assertEquals(0, ((Number) merchantStation(ARCHIVE_MERCHANT, ARCHIVE_RUN).get("pendingVerificationCount")).intValue());

        // 玩家可以继续:到下一站签到后,该站变为可玩并能提交。
        it.arrive(PLAYER_A, CLOCK_NODE);
        Map<String, Object> clockArrived = playerNodes(PLAYER_A).get(CLOCK_NODE);
        assertEquals("IN_PROGRESS", clockArrived.get("personalState"));
        assertEquals(Boolean.TRUE, clockArrived.get("playable"));
        assertEquals("APPLIED", it.submit(PLAYER_A, CLOCK_NODE, "CLOCK_TEXT", "1888").getOutcome());
        assertEquals("AWAITING_VERIFICATION", playerNodes(PLAYER_A).get(CLOCK_NODE).get("personalState"));
        assertEquals(1, it.db.queryForObject("select count(*) from game_player_node_outcome where game_session_id=?",
                Integer.class, SESSION), "a pending submission at the next station completes nothing");

        // 同一 requestId 重放只回放回执,不再写结果。
        int revisionBeforeReplay = it.revision();
        assertTrue(it.runtime.command(GameActorContext.merchant(ARCHIVE_MERCHANT), approveCommand).isReplayed());
        assertEquals(revisionBeforeReplay, it.revision());
        assertEquals(1, approvedJoin().size());
        // 另一位玩家完全不受影响。
        assertEquals(0, it.db.queryForObject("select count(*) from game_player_node_outcome where member_id=?",
                Integer.class, PLAYER_B));
        assertEquals("AVAILABLE", playerNodes(PLAYER_B).get(ARCHIVE_NODE).get("personalState"));
        System.out.println("SC-SCAN-LEGAL readback: sql1=" + approvedJoin() + " sql3=" + fallbackCompleted(PLAYER_A)
                + " progress=" + it.db.queryForList("select member_id,node_id,status from player_node_progress order by id")
                + " archiveNode=" + Map.of("personalState", archive.get("personalState"), "completionSource",
                archive.get("completionSource"), "completionOrder", archive.get("completionOrder"))
                + " clockNode=" + clock.get("personalState") + " marketNode=" + market.get("personalState") + " merchantRecap=" + recap);
    }

    @Test
    void onlyAnApprovedFallbackBindingCompletesTheSourceStationAlongside() {
        it.db.update("update game_station_run set fallback_node_id=?,fallback_plan_code='MARKET_TO_ARCHIVE',"
                + "fallback_plan_version=1 where id=?", ARCHIVE_NODE, MARKET_RUN);
        Map<String, List<Map<String, Object>>> ledgersBefore = it.snapshot(LEDGERS);
        assertEquals("旧档案馆", ((Map<?, ?>) playerNodes(PLAYER_A).get(MARKET_NODE).get("fallback")).get("targetNodeName"));

        long submissionId = approveArchiveSubmission();

        assertEquals(1, approvedJoin().size(), "SQL (1) still yields exactly one MERCHANT_APPROVAL row");
        List<Map<String, Object>> outcomes = it.db.queryForList("select node_id,station_run_id,status,completion_source,"
                + "source_submission_id,fallback_plan_code,fallback_plan_version from game_player_node_outcome"
                + " where game_session_id=? order by id", SESSION);
        assertEquals(2, outcomes.size(), "readback: " + outcomes);
        assertEquals(ARCHIVE_NODE, ((Number) outcomes.get(0).get("node_id")).longValue());
        assertEquals("COMPLETED", outcomes.get(0).get("status"));
        assertEquals(submissionId, ((Number) outcomes.get(0).get("source_submission_id")).longValue());
        assertEquals(MARKET_NODE, ((Number) outcomes.get(1).get("node_id")).longValue());
        assertEquals(MARKET_RUN, ((Number) outcomes.get(1).get("station_run_id")).longValue());
        assertEquals("FALLBACK_COMPLETED", outcomes.get(1).get("status"));
        assertEquals("FALLBACK_PLAN", outcomes.get(1).get("completion_source"));
        assertNull(outcomes.get(1).get("source_submission_id"), "fallback outcome carries no fake submission");
        assertEquals("MARKET_TO_ARCHIVE", outcomes.get(1).get("fallback_plan_code"));
        assertEquals(1, ((Number) outcomes.get(1).get("fallback_plan_version")).intValue());
        assertEquals(1, fallbackCompleted(PLAYER_A), "SQL (3) is 1 only because the binding is approved");
        assertEquals(0, it.db.queryForObject("select count(*) from game_player_node_outcome where node_id=?",
                Integer.class, CLOCK_NODE), "the unrelated station stays open");

        Map<Long, Map<String, Object>> nodes = playerNodes(PLAYER_A);
        assertEquals("COMPLETED", nodes.get(ARCHIVE_NODE).get("personalState"));
        assertEquals("FALLBACK_COMPLETED", nodes.get(MARKET_NODE).get("personalState"));
        assertEquals("FALLBACK_PLAN", nodes.get(MARKET_NODE).get("completionSource"));
        assertEquals("AVAILABLE", nodes.get(CLOCK_NODE).get("personalState"));
        assertEquals(ledgersBefore, it.snapshot(LEDGERS));
    }
}
