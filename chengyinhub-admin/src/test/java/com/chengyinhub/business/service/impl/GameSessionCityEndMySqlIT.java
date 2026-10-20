package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.service.GameSessionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.chengyinhub.business.service.impl.GameSessionLedgerMySqlFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SC-CITY-END [P1] 真库验收:同一局两名玩家,A 在旧档案馆经商家核验完成,B 到店提交但没被核验;
 * 导演 FINISH 后复盘计数等于结果行,订单 / 库存 quota_used / 权益 / 奖励只等于本局实际履约,
 * FINISH 本身与第二次 FINISH 都不改任何业务行。
 *
 * <p>本局的实际履约在夹具里写成"已经发生"的台账:两张已付票、A 在旧档案馆核销了一次章节供给
 * (registration_entitlement 已得 + merchant_chapter_offer.quota_used=1)。游戏运行时按设计不写这些表,
 * 所以对账口径是:提交 / 核验 / FINISH 前后这些表逐行相等,且 quota_used = 已得权益数,奖励表为空。
 * 奖励发放并发归 PR #294,这里不碰。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GameSessionCityEndMySqlIT {

    final GameSessionLedgerMySqlFixture it = new GameSessionLedgerMySqlFixture();

    /** FINISH 不许动的本局业务行(会话行与站点行的状态迁移单独断言)。 */
    static final List<String> PLAY_ROWS = List.of("game_task_submission", "game_player_node_outcome",
            "game_story_state", "game_team_role_assignment", "play_team", "play_team_member");

    @BeforeAll void open() throws Exception { it.open("city_end"); }
    @AfterAll void close() { it.close(); }

    @BeforeEach void seed() {
        it.seed();
        it.db.update("insert into coop_order(id,topic_id,from_id,to_id,to_type,share_mode,status,create_time,del_flag)"
                + " values(701,?,?,?,'merchant',0,0,now(),0)", TOPIC, DIRECTOR, ARCHIVE_MERCHANT);
        it.db.update("insert into merchant_chapter_offer(id,topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,status)"
                + " values(801,?,1,?,'TRAFFIC',20,1,1)", TOPIC, ARCHIVE_MERCHANT);
        it.db.update("insert into registration_entitlement(id,registration_id,chapter_id,topic_id,status,redeemed_at,create_time)"
                + " values(901,501,1,?,1,now(),now()),(902,502,1,?,0,null,now())", TOPIC, TOPIC);
        it.arrive(PLAYER_A, ARCHIVE_NODE);
        it.arrive(PLAYER_B, ARCHIVE_NODE);
    }

    List<String> everything() {
        List<String> tables = new ArrayList<>(PLAY_ROWS);
        tables.addAll(LEDGERS);
        return tables;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutGeneratedAt(Map<String, Object> exported) {
        Map<String, Object> recap = new LinkedHashMap<>((Map<String, Object>) exported.get("recap"));
        recap.remove("generatedAt");
        return recap;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> station(Map<String, Object> recap, long nodeId) {
        for (Map<String, Object> row : (List<Map<String, Object>>) recap.get("stations")) {
            if (((Number) row.get("nodeId")).longValue() == nodeId) return row;
        }
        throw new AssertionError("recap lacks node " + nodeId + ": " + recap);
    }

    static int number(Object value) { return ((Number) value).intValue(); }

    int count(String sql, Object... args) { return it.db.queryForObject(sql, Integer.class, args); }

    @Test
    @SuppressWarnings("unchecked")
    void finishFreezesRecapToOutcomeRowsAndLeavesOrdersStockRewardsAsFulfilled() {
        Map<String, List<Map<String, Object>>> ledgersBeforePlay = it.snapshot(LEDGERS);

        // A:提交 → 旧档案馆商家核验通过;B:提交后一直待核验,没完成。
        assertEquals("APPLIED", it.submit(PLAYER_A, ARCHIVE_NODE, "ARCHIVE_TEXT", "1998").getOutcome());
        assertEquals("APPLIED", it.submit(PLAYER_B, ARCHIVE_NODE, "ARCHIVE_TEXT", "2001").getOutcome());
        long submissionA = it.pendingSubmission(PLAYER_A, ARCHIVE_NODE);
        long submissionB = it.pendingSubmission(PLAYER_B, ARCHIVE_NODE);
        assertEquals("APPLIED", it.runtime.command(GameActorContext.merchant(ARCHIVE_MERCHANT),
                it.verify("end-approve-a", ARCHIVE_NODE, submissionA, "APPROVE")).getOutcome());
        assertEquals(ledgersBeforePlay, it.snapshot(LEDGERS), "playing the session writes no order/stock/reward row");

        Map<String, Object> recapRunning = it.runtime.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY);
        Map<String, List<Map<String, Object>>> playBefore = it.snapshot(everything());
        List<Map<String, Object>> stationsBefore = it.db.queryForList("select id,status,revision from game_station_run order by id");
        int receiptsBefore = count("select count(*) from game_command_receipt");
        int revisionBefore = it.revision();

        GameSessionCommand finish = it.command("end-finish", "FINISH", 0L);
        finish.setNodeId(null);
        GameCommandReceiptView finished = it.runtime.command(GameActorContext.club(DIRECTOR), finish);
        assertEquals("APPLIED", finished.getOutcome());

        // FINISH:会话终态 + 站点全部 CLOSED,其余业务行一行不变。
        Map<String, Object> session = it.db.queryForMap("select status,revision,finished_by,finished_at from game_session where id=?", SESSION);
        assertEquals("FINISHED", session.get("status"));
        assertEquals(revisionBefore + 1, number(session.get("revision")));
        assertEquals(DIRECTOR, ((Number) session.get("finished_by")).longValue());
        assertNotNull(session.get("finished_at"));
        List<Map<String, Object>> stationsAfter = it.db.queryForList("select id,status,revision from game_station_run order by id");
        for (int i = 0; i < stationsAfter.size(); i++) {
            assertEquals("CLOSED", stationsAfter.get(i).get("status"));
            assertEquals(number(stationsBefore.get(i).get("revision")) + 1, number(stationsAfter.get(i).get("revision")));
        }
        assertEquals(playBefore, it.snapshot(everything()), "FINISH changes no submission/outcome/order/stock/reward row");
        assertEquals(receiptsBefore + 1, count("select count(*) from game_command_receipt"));
        assertEquals("PENDING", it.db.queryForObject("select status from game_task_submission where id=?", String.class, submissionB),
                "an unverified submission is not completed by FINISH");

        // 复盘计数 = 结果行。
        Map<String, Object> recap = withoutGeneratedAt(it.runtime.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY));
        Map<String, Object> funnel = (Map<String, Object>) recap.get("funnel");
        assertEquals(count("select count(distinct member_id) from game_player_node_outcome where game_session_id=? and status='COMPLETED'", SESSION),
                number(funnel.get("normalCompleters")), "recap " + recap);
        assertEquals(1, number(funnel.get("normalCompleters")));
        assertEquals(count("select count(distinct member_id) from game_player_node_outcome where game_session_id=? and status='FALLBACK_COMPLETED'", SESSION),
                number(funnel.get("fallbackCompleters")));
        assertEquals(0, number(funnel.get("fallbackCompleters")));
        assertEquals(count("select count(distinct member_id) from game_task_submission where game_session_id=?", SESSION),
                number(funnel.get("taskSubmitters")));
        assertEquals(2, number(funnel.get("taskSubmitters")));
        assertEquals(2, number(funnel.get("paidPlayers")));
        assertEquals(2, number(funnel.get("arrivedPlayers")));
        assertEquals(0, number(funnel.get("finishedTeams")), "no team completed all three stations");
        Map<String, Object> archive = station(recap, ARCHIVE_NODE);
        assertEquals(count("select count(*) from game_player_node_outcome where station_run_id=? and status='COMPLETED'", ARCHIVE_RUN),
                number(archive.get("normalCompletedCount")));
        assertEquals(1, number(archive.get("normalCompletedCount")));
        assertEquals(2, number(archive.get("submissionCount")));
        assertEquals(2, number(archive.get("arrivedPlayers")));
        for (long other : new long[]{CLOCK_NODE, MARKET_NODE}) {
            assertEquals(0, number(station(recap, other).get("normalCompletedCount")));
            assertEquals(0, number(station(recap, other).get("fallbackCompletedCount")));
        }
        assertEquals(count("select count(*) from game_player_node_outcome where game_session_id=?", SESSION),
                ((List<Map<String, Object>>) recap.get("stations")).stream()
                        .mapToInt(s -> number(s.get("normalCompletedCount")) + number(s.get("fallbackCompletedCount"))).sum());
        assertEquals(withoutGeneratedAt(recapRunning), recap, "FINISH does not change the recap numbers");

        // 订单 / 库存 / 权益 / 奖励 = 本局实际履约。
        assertEquals(ledgersBeforePlay, it.snapshot(LEDGERS));
        assertEquals(2, count("select count(*) from cms_registration where owner_type=2 and owner_id=? and payment_status=2"
                + " and registration_status=2", ACTIVITY));
        assertEquals(count("select count(*) from registration_entitlement where topic_id=? and status=1", TOPIC),
                count("select coalesce(sum(quota_used),0) from merchant_chapter_offer where topic_id=?", TOPIC));
        assertEquals(1, count("select quota_used from merchant_chapter_offer where id=801"));
        for (String reward : List.of("reward_pending", "reward_drop_log", "cms_topic_completion_coupon_reward_log", "redemption",
                "coop_group_verify")) {
            assertEquals(0, count("select count(*) from " + reward), reward + " gained a row the session never fulfilled");
        }

        // 第二次 FINISH:同 requestId 只回放;新 requestId 被拒(按控制器路径记一张 FAILED 回执),业务行与复盘都不变。
        Map<String, List<Map<String, Object>>> afterFirst = it.snapshot(everything());
        Map<String, Object> sessionAfterFirst = it.db.queryForMap("select * from game_session where id=?", SESSION);
        List<Map<String, Object>> stationsAfterFirst = it.db.queryForList("select * from game_station_run order by id");
        assertTrue(it.runtime.command(GameActorContext.club(DIRECTOR), finish).isReplayed());
        GameSessionCommand again = it.command("end-finish-again", "FINISH", 0L);
        again.setNodeId(null);
        GameSessionException refused = it.commandLikeController(GameActorContext.club(DIRECTOR), again);
        assertNotNull(refused.getErrorCode());
        // FINISH 之后商家也不能再把 B 的待核验提交改成完成。
        GameSessionException lateApprove = it.commandLikeController(GameActorContext.merchant(ARCHIVE_MERCHANT),
                it.verify("end-late-approve-b", ARCHIVE_NODE, submissionB, "APPROVE"));
        assertNotNull(lateApprove.getErrorCode());
        assertEquals(afterFirst, it.snapshot(everything()));
        assertEquals(sessionAfterFirst, it.db.queryForMap("select * from game_session where id=?", SESSION));
        assertEquals(stationsAfterFirst, it.db.queryForList("select * from game_station_run order by id"));
        assertEquals(1, count("select count(*) from game_command_receipt where action='FINISH' and outcome='APPLIED'"));
        assertEquals(0, count("select count(*) from game_command_receipt where outcome='APPLIED' and request_id in"
                + " ('end-finish-again','end-late-approve-b')"));
        assertEquals(recap, withoutGeneratedAt(it.runtime.exportRecap(GameActorContext.club(DIRECTOR), ACTIVITY)));
        System.out.println("SC-CITY-END readback: recap.funnel=" + funnel + " archive=" + archive
                + " finishRefused=" + refused.getErrorCode() + " lateApproveRefused=" + lateApprove.getErrorCode()
                + " receipts=" + it.db.queryForList("select request_id,action,outcome,reason_code from game_command_receipt order by id"));
    }
}
