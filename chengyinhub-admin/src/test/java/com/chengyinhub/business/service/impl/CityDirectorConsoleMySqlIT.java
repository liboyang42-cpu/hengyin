package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.IImService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.chengyinhub.business.service.impl.GameSessionLedgerMySqlFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布验收 P1「导演台 / 商家城市节点 / 玩家城市局」后端半段,真 MySQL 8 + 真 DDL + 真 mapper XML + 真事务代理。
 *
 * <p>复用 {@link GameSessionLedgerMySqlFixture}(SC-SCAN-LEGAL / SC-CITY-END 的同一套夹具),只在其上补:
 * 主题节点 cms_topic_node / cms_registration_merchant(生产 DDL,供 PREPARE 的 INSERT...SELECT 真跑)、
 * CmsTopicMapper(只读主题配置,mock)和 IImService(IM 投递通道,mock —— 外部通道不在本仓库验证范围)。
 *
 * <p>卡片对应(详见 docs/delivery/release-closeout-20261022/acceptance-p1-backend.md):
 * <ul>
 *   <li>D1 准备 / M5 商家节点就绪 / D3 名单 / D2 开局 / D8 冲突:{@link #prepareReadyRosterStartChainOnRealRows}</li>
 *   <li>D8 并发准备:{@link #concurrentPrepareCreatesExactlyOneSessionAndOneStationSet}</li>
 *   <li>D4 全员广播 / D5 定向广播 / D9 存活范围:{@link #broadcastReachesOnlyPaidActiveMembersInScope}、
 *       {@link #sameRequestRetryResendsOnlyTheFailedDelivery}</li>
 *   <li>D6 现场事件 / M6 商家暂停 / P9 暂停后读回:{@link #pauseAndResumeTouchOnlyTheChosenStationAndPlayerReadsItBack}</li>
 *   <li>P2 队伍隔离 / P3 账号隔离 / P1 进度读回:{@link #playerViewIsScopedToOwnTeamAndOwnAccount}</li>
 * </ul>
 * 负控:每条正向断言旁边都有「同局别的行一行不变」或「越权/过期 → 零写入」的对照,不靠退出码。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CityDirectorConsoleMySqlIT {

    /** A 队未付费队友(不应进入名单/广播/角色门槛);B 队已退队成员;别场队伍与其成员;A 队已付费第二名队友。 */
    static final long UNPAID_A = 8L, EXITED_B = 10L, OTHER_ACTIVITY = 71L, OTHER_TEAM = 95L, OTHER_PLAYER = 11L,
            MATE_A = 12L, STRANGER_CLUB = 77L;
    /** 本主题已下线节点、待审商家节点、别的主题节点:PREPARE 一个都不许带进本局。 */
    static final long OFFLINE_NODE = 14L, PENDING_NODE = 15L, FOREIGN_NODE = 21L;
    /** 已过审、但所属商家已退出本章供给(offer 停用 + 退出理由)的节点:玩家看不到,PREPARE 也不许建站。 */
    static final long WITHDRAWN_NODE = 16L, WITHDRAWN_CHAPTER = 66L, WITHDRAWN_MERCHANT = 340L;

    /** 夹具 CONFIG 是"已开局快照";PREPARE 走发布校验,每站还须带商家操作说明与防剧透提醒。 */
    static final String PUBLISHED_CONFIG = CONFIG.replace("\"verificationRequired\":true}}",
            "\"verificationRequired\":true},\"merchantInstruction\":\"核对年份后通过\",\"hiddenInfoReminder\":\"不要向玩家透露答案\"}");

    final GameSessionLedgerMySqlFixture it = new GameSessionLedgerMySqlFixture();
    final IImService im = mock(IImService.class);
    final AtomicLong messageIds = new AtomicLong(9000);

    @BeforeAll
    void open() throws Exception {
        it.open("city_director");
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")), StandardCharsets.UTF_8);
        for (String table : List.of("cms_topic_node", "cms_registration_merchant")) {
            it.db.execute(statement(base, "CREATE TABLE `" + table + "` ("));
        }
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenAnswer(call -> {
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setGameModuleConfigJson(PUBLISHED_CONFIG);
            return topic;
        });
        ReflectionTestUtils.setField(it.impl, "topicMapper", topics);
        ReflectionTestUtils.setField(it.impl, "imService", im);
    }

    @AfterAll
    void close() { it.close(); }

    @BeforeEach
    void seed() {
        it.seed();
        for (String table : List.of("game_broadcast_delivery", "game_broadcast", "cms_topic_node", "cms_registration_merchant")) {
            it.db.update("delete from " + table);
        }
        reset(im);
        when(im.sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString()))
                .thenAnswer(call -> messageIds.incrementAndGet());
        // 主题节点:三站上线且审核通过;另有下线节点、待审商家节点、别的主题节点做负控。
        it.db.update("insert into cms_topic_node(id,topic_id,name,merchant_member_id,node_audit_status,status) values"
                        + "(?,?,'旧档案馆',?,1,1),(?,?,'旧钟楼',?,1,1),(?,?,'旧集市',?,1,1),"
                        + "(?,?,'已下线节点',?,1,0),(?,?,'待审节点',?,0,1),(?,?,'别的主题',?,1,1)",
                ARCHIVE_NODE, TOPIC, ARCHIVE_MERCHANT, CLOCK_NODE, TOPIC, CLOCK_MERCHANT, MARKET_NODE, TOPIC, MARKET_MERCHANT,
                OFFLINE_NODE, TOPIC, ARCHIVE_MERCHANT, PENDING_NODE, TOPIC, CLOCK_MERCHANT, FOREIGN_NODE, TOPIC + 1, ARCHIVE_MERCHANT);
        it.db.update("insert into cms_topic_node(id,topic_id,chapter_id,name,merchant_member_id,node_audit_status,status)"
                + " values(?,?,?,'已退出商家节点',?,1,1)", WITHDRAWN_NODE, TOPIC, WITHDRAWN_CHAPTER, WITHDRAWN_MERCHANT);
        it.db.update("insert into merchant_chapter_offer(topic_id,chapter_id,merchant_id,terms_mode,quota_total,quota_used,"
                + "status,withdraw_reason) values(?,?,?,'TRAFFIC',0,0,0,'商家主动退出')", TOPIC, WITHDRAWN_CHAPTER, WITHDRAWN_MERCHANT);
        // 名单负控:A 队未付费队友、B 队已退队成员、别场队伍里已付费的玩家;A 队再加一名已付费队友(账号隔离用)。
        it.db.update("insert into play_team(id,owner_type,owner_id,leader_member_id,status) values(?,2,?,?,2)",
                OTHER_TEAM, OTHER_ACTIVITY, OTHER_PLAYER);
        it.db.update("insert into play_team_member(team_id,member_id,role,status) values(?,?,0,1),(?,?,0,0),(?,?,1,1),(?,?,0,1)",
                TEAM_A, UNPAID_A, TEAM_B, EXITED_B, OTHER_TEAM, OTHER_PLAYER, TEAM_A, MATE_A);
        registration(503, OTHER_ACTIVITY, OTHER_PLAYER, 2);
        registration(504, ACTIVITY, UNPAID_A, 1);
        registration(505, ACTIVITY, EXITED_B, 2);
        registration(506, ACTIVITY, MATE_A, 2);
        it.db.update("insert into game_team_role_assignment(game_session_id,team_id,member_id,role_code,role_name,status,"
                + "assigned_by,revision,create_time,update_time) values(?,?,?,'DETECTIVE','侦探','CONFIRMED',?,0,now(),now())",
                SESSION, TEAM_A, MATE_A, DIRECTOR);
    }

    void registration(long id, long activity, long member, int paymentStatus) {
        it.db.update("insert into cms_registration(id,registration_no,owner_type,owner_id,ticket_id,ticket_price,member_id,"
                + "original_amount,total_amount,payable_amount,pay_expire_time,payment_status,registration_status,create_time)"
                + " values(?,?,2,?,601,39.00,?,39.00,39.00,39.00,now(),?,2,now())",
                id, "REG-IT-" + id, activity, member, paymentStatus);
    }

    /** 抹掉夹具里那局 RUNNING 的会话,回到「主理人还没点准备」的初态。 */
    void unprepared() {
        for (String table : List.of("game_command_receipt", "game_team_role_assignment", "game_station_run", "game_session")) {
            it.db.update("delete from " + table);
        }
    }

    GameSessionCommand prepare(String requestId) {
        GameSessionCommand c = new GameSessionCommand();
        c.setActivityId(ACTIVITY);
        c.setRequestId(requestId);
        c.setAction("PREPARE");
        return c;
    }

    long sessionId() {
        return it.db.queryForObject("select id from game_session where activity_id=?", Long.class, ACTIVITY);
    }

    int sessionRevision() {
        return it.db.queryForObject("select revision from game_session where activity_id=?", Integer.class, ACTIVITY);
    }

    GameSessionCommand command(String requestId, String action, Long nodeId) {
        GameSessionCommand c = new GameSessionCommand();
        c.setActivityId(ACTIVITY);
        c.setNodeId(nodeId);
        c.setRequestId(requestId);
        c.setExpectedRevision(sessionRevision());
        c.setAction(action);
        return c;
    }

    List<Map<String, Object>> stations() {
        return it.db.queryForList("select node_id,station_code,merchant_member_id,source_type,status,revision,capacity,"
                + "pause_reason_code,resume_eta from game_station_run order by node_id");
    }

    Set<Long> deliveredTo(Object broadcastId) {
        return new TreeSet<>(it.db.queryForList("select member_id from game_broadcast_delivery where broadcast_id=?",
                Long.class, broadcastId));
    }

    int count(String sql, Object... args) { return it.db.queryForObject(sql, Integer.class, args); }

    static String at(long offsetMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(System.currentTimeMillis() + offsetMillis));
    }

    // ———————————————————— D1 / M5 / D3 / D2 / D8 ————————————————————

    @Test
    @SuppressWarnings("unchecked")
    void prepareReadyRosterStartChainOnRealRows() {
        unprepared();

        // D1 准备:一局一行、三站逐站来自本主题上线且审核通过的节点。
        GameCommandReceiptView prepared = it.runtime.command(GameActorContext.club(DIRECTOR), prepare("dc-prepare"));
        assertEquals("APPLIED", prepared.getOutcome());
        Map<String, Object> session = it.db.queryForMap(
                "select status,revision,prepared_by,started_by from game_session where activity_id=?", ACTIVITY);
        assertEquals("PREPARING", session.get("status"));
        assertEquals(0, ((Number) session.get("revision")).intValue());
        assertEquals(DIRECTOR, ((Number) session.get("prepared_by")).longValue());
        assertNull(session.get("started_by"));
        List<Map<String, Object>> invited = stations();
        assertEquals(List.of(ARCHIVE_NODE, CLOCK_NODE, MARKET_NODE),
                invited.stream().map(r -> ((Number) r.get("node_id")).longValue()).toList(),
                "只带本主题上线且审核通过的节点;下线/待审/别的主题/商家已退出的节点都不进本局");
        assertEquals(List.of("ARCHIVE", "CLOCK_TOWER", "MARKET"), invited.stream().map(r -> r.get("station_code")).toList());
        assertEquals(List.of(ARCHIVE_MERCHANT, CLOCK_MERCHANT, MARKET_MERCHANT),
                invited.stream().map(r -> ((Number) r.get("merchant_member_id")).longValue()).toList());
        invited.forEach(r -> assertEquals("INVITED", r.get("status")));

        // D8 重复准备:换 requestId 再点一次,不生第二局、不重抓站点。
        GameCommandReceiptView again = it.runtime.command(GameActorContext.club(DIRECTOR), prepare("dc-prepare-2"));
        assertEquals("APPLIED", again.getOutcome());
        assertEquals(Boolean.FALSE, again.getResult().get("prepared"), "已有会话时第二次准备只回读,不新建");
        assertEquals(1, count("select count(*) from game_session"));
        assertEquals(invited, stations(), "重复准备不改任何站点行");

        // 越权:不是本场主理人的俱乐部账号 → 拒绝且不写回执。
        int receipts = count("select count(*) from game_command_receipt");
        GameSessionException stranger = assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.club(STRANGER_CLUB), prepare("dc-stranger")));
        assertEquals("GAME_FORBIDDEN_SCOPE", stranger.getErrorCode());
        assertEquals(receipts, count("select count(*) from game_command_receipt"));

        // D2 未就绪不许开局:站点还在 INVITED → GAME_STATION_NOT_READY,零站点改动、会话不动。
        GameSessionException early = assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.club(DIRECTOR), command("dc-start-early", "START", null)));
        assertEquals("GAME_STATE_CONFLICT", early.getErrorCode(), "PREPARING 不能直接开局(故事模式须先 READY)");
        assertEquals(invited, stations());
        assertEquals(0, sessionRevision());

        // M5 商家节点就绪:别家商家碰不到我的站点;本站商家 接受 → 就绪,服务时段/容量真落库。
        GameSessionException foreign = assertThrows(GameSessionException.class, () -> it.runtime.command(
                GameActorContext.merchant(CLOCK_MERCHANT), command("dc-accept-foreign", "STATION_ACCEPT", ARCHIVE_NODE)));
        assertEquals("GAME_FORBIDDEN_SCOPE", foreign.getErrorCode());
        assertEquals(invited, stations(), "别家商家的接受动作零写入");
        long[][] merchants = {{ARCHIVE_NODE, ARCHIVE_MERCHANT}, {CLOCK_NODE, CLOCK_MERCHANT}, {MARKET_NODE, MARKET_MERCHANT}};
        for (long[] pair : merchants) {
            GameActorContext merchant = GameActorContext.merchant(pair[1]);
            assertEquals("APPLIED", it.runtime.command(merchant,
                    command("dc-accept-" + pair[0], "STATION_ACCEPT", pair[0])).getOutcome());
            GameSessionCommand ready = command("dc-ready-" + pair[0], "STATION_READY", pair[0]);
            ready.getPayload().put("serviceStartAt", at(-30 * 60_000L));
            ready.getPayload().put("serviceEndAt", at(90 * 60_000L));
            ready.getPayload().put("capacity", 12);
            ready.getPayload().put("checklist", List.of());
            assertEquals("APPLIED", it.runtime.command(merchant, ready).getOutcome());
        }
        for (Map<String, Object> row : stations()) {
            assertEquals("READY", row.get("status"), "商家就绪后站点 READY:" + row);
            assertEquals(2, ((Number) row.get("revision")).intValue(), "接受 + 就绪各一次站点版本");
            assertEquals(12, ((Number) row.get("capacity")).intValue());
        }
        assertEquals(3, count("select count(*) from game_station_run where service_start_at < now() and service_end_at > now()"),
                "服务时段按商家提交值落库");
        assertEquals("PREPARING", it.db.queryForObject("select status from game_session where id=?", String.class, sessionId()),
                "站点全就绪但角色未确认,仍是 PREPARING");

        // 角色分配 + 玩家确认:未付费 / 已退队成员不计入门槛。
        for (long[] team : new long[][]{{TEAM_A, PLAYER_A}, {TEAM_B, PLAYER_B}}) {
            GameSessionCommand assign = command("dc-assign-" + team[0], "ASSIGN_ROLES", null);
            assign.getPayload().put("teamId", team[0]);
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(new LinkedHashMap<>(Map.of("memberId", team[1], "roleCode", "DETECTIVE")));
            if (team[0] == TEAM_A) rows.add(new LinkedHashMap<>(Map.of("memberId", MATE_A, "roleCode", "DETECTIVE")));
            assign.getPayload().put("assignments", rows);
            assertEquals("APPLIED", it.runtime.command(GameActorContext.club(DIRECTOR), assign).getOutcome());
        }
        GameSessionCommand unpaidAssign = command("dc-assign-unpaid", "ASSIGN_ROLES", null);
        unpaidAssign.getPayload().put("teamId", TEAM_A);
        unpaidAssign.getPayload().put("assignments", List.of(Map.of("memberId", UNPAID_A, "roleCode", "DETECTIVE")));
        assertEquals("GAME_FORBIDDEN_SCOPE", assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.club(DIRECTOR), unpaidAssign)).getErrorCode(),
                "未付费队友不能被分配角色");
        for (long player : new long[]{PLAYER_A, MATE_A, PLAYER_B}) {
            assertEquals("APPLIED", it.runtime.command(GameActorContext.player(player),
                    command("dc-confirm-" + player, "CONFIRM_ROLE", null)).getOutcome());
        }
        assertEquals("READY", it.db.queryForObject("select status from game_session where id=?", String.class, sessionId()));

        // D3 名单:只含本场已付费、在队的成员;没有手机号 / 坐标 / 证据字段。
        GameSessionView club = it.runtime.view(GameActorContext.club(DIRECTOR), ACTIVITY);
        List<Map<String, Object>> roster = (List<Map<String, Object>>) club.getClub().get("roles");
        Set<Long> rosterMembers = new TreeSet<>();
        for (Map<String, Object> row : roster) {
            rosterMembers.add(((Number) row.get("memberId")).longValue());
            for (String key : row.keySet()) {
                assertFalse(key.toLowerCase().matches(".*(phone|mobile|lat|lng|longitude|latitude|evidence).*"),
                        "名单不得带隐私字段:" + key);
            }
        }
        assertEquals(new TreeSet<>(Set.of(PLAYER_A, MATE_A, PLAYER_B)), rosterMembers,
                "未付费、已退队、别场的成员都不进导演台名单");
        Map<String, Object> readiness = (Map<String, Object>) club.getClub().get("readiness");
        assertEquals(Boolean.TRUE, readiness.get("teamsReady"));
        assertEquals(List.of(), readiness.get("blockers"));
        assertTrue(club.getAvailableActions().contains("START"), "就绪后导演台出现开局按钮:" + club.getAvailableActions());

        // D8 过期版本开局:零写入。
        List<Map<String, Object>> readyRows = stations();
        int readyRevision = sessionRevision();
        GameSessionCommand stale = command("dc-start-stale", "START", null);
        stale.setExpectedRevision(readyRevision - 1);
        assertEquals("GAME_STATE_CONFLICT", assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.club(DIRECTOR), stale)).getErrorCode());
        assertEquals(readyRows, stations());
        assertEquals(readyRevision, sessionRevision());

        // D2 开局:会话 RUNNING、三站全 ACTIVE(站点版本 +1),started_by = 导演。
        assertEquals("APPLIED", it.runtime.command(GameActorContext.club(DIRECTOR), command("dc-start", "START", null)).getOutcome());
        Map<String, Object> running = it.db.queryForMap("select status,revision,started_by,started_at from game_session where id=?", sessionId());
        assertEquals("RUNNING", running.get("status"));
        assertEquals(readyRevision + 1, ((Number) running.get("revision")).intValue());
        assertEquals(DIRECTOR, ((Number) running.get("started_by")).longValue());
        assertNotNull(running.get("started_at"));
        for (Map<String, Object> row : stations()) {
            assertEquals("ACTIVE", row.get("status"));
            assertEquals(3, ((Number) row.get("revision")).intValue());
        }

        // D8 重复开局:已 RUNNING → 冲突,站点零写入。
        List<Map<String, Object>> activeRows = stations();
        assertEquals("GAME_STATE_CONFLICT", assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.club(DIRECTOR), command("dc-start-again", "START", null))).getErrorCode());
        assertEquals(activeRows, stations());
        System.out.println("P1-DIRECTOR readback: stations=" + activeRows + " roster=" + rosterMembers
                + " receipts=" + it.db.queryForList("select request_id,action,outcome from game_command_receipt order by id"));
    }

    @Test
    void concurrentPrepareCreatesExactlyOneSessionAndOneStationSet() throws Exception {
        unprepared();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String requestId = "dc-race-" + i;
                Callable<Object> task = () -> {
                    go.await();
                    try {
                        return it.runtime.command(GameActorContext.club(DIRECTOR), prepare(requestId)).getResult().get("prepared");
                    } catch (GameSessionException conflict) {
                        return conflict.getErrorCode();
                    }
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) outcomes.add(result.get(60, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Boolean.TRUE::equals).count(), "恰好一个准备真正建局:" + outcomes);
            assertEquals(1, count("select count(*) from game_session where activity_id=?", ACTIVITY));
            assertEquals(3, count("select count(*) from game_station_run"), "并发准备不重复抓站点");
            assertEquals(3, count("select count(distinct node_id) from game_station_run"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ———————————————————— D4 / D5 / D9 ————————————————————

    GameCommandReceiptView broadcast(String requestId, String targetType, Long targetId, String roleCode) {
        GameSessionCommand c = command(requestId, "BROADCAST", null);
        c.getPayload().put("targetType", targetType);
        c.getPayload().put("content", "集合点改到旧钟楼");
        if (targetId != null) c.getPayload().put("targetId", targetId);
        if (roleCode != null) c.getPayload().put("roleCode", roleCode);
        return it.runtime.command(GameActorContext.club(DIRECTOR), c);
    }

    @Test
    void broadcastReachesOnlyPaidActiveMembersInScope() {
        // D4 全员:A 队两名已付费成员 + B 队一名;未付费 / 已退队 / 别场的一个都不投。
        int revision = sessionRevision();
        GameCommandReceiptView all = broadcast("dc-bc-all", "ALL", null, null);
        assertEquals("APPLIED", all.getOutcome());
        Object allId = all.getResult().get("broadcastId");
        assertEquals(new TreeSet<>(Set.of(PLAYER_A, MATE_A, PLAYER_B)), deliveredTo(allId));
        assertEquals(3, count("select count(*) from game_broadcast_delivery where broadcast_id=? and status='REACHED'", allId));
        assertEquals("SENT", it.db.queryForObject("select status from game_broadcast where id=?", String.class, allId));
        assertEquals(revision + 1, sessionRevision(), "广播只加一次会话版本");
        verify(im, never()).sendSystemInNestedTransaction(eq(UNPAID_A), any(), any(), any());
        verify(im, never()).sendSystemInNestedTransaction(eq(EXITED_B), any(), any(), any());
        verify(im, never()).sendSystemInNestedTransaction(eq(OTHER_PLAYER), any(), any(), any());

        // D5 定向:按队伍只投本队;按角色只投持该角色者。
        Object teamId = broadcast("dc-bc-team", "TEAM", TEAM_A, null).getResult().get("broadcastId");
        assertEquals(new TreeSet<>(Set.of(PLAYER_A, MATE_A)), deliveredTo(teamId));
        it.db.update("update game_team_role_assignment set role_code='WITNESS' where member_id=?", PLAYER_B);
        Object roleId = broadcast("dc-bc-role", "ROLE", null, "WITNESS").getResult().get("broadcastId");
        assertEquals(new TreeSet<>(Set.of(PLAYER_B)), deliveredTo(roleId));

        // 别场的队伍:越权拒绝,零广播行、零投递行。
        int broadcasts = count("select count(*) from game_broadcast");
        int deliveries = count("select count(*) from game_broadcast_delivery");
        assertEquals("GAME_FORBIDDEN_SCOPE", assertThrows(GameSessionException.class,
                () -> broadcast("dc-bc-foreign", "TEAM", OTHER_TEAM, null)).getErrorCode());
        assertEquals(broadcasts, count("select count(*) from game_broadcast"));
        assertEquals(deliveries, count("select count(*) from game_broadcast_delivery"));

        // D9 存活范围:退款的成员、退队的成员立即离开投递范围。
        it.db.update("update cms_registration set payment_status=3 where member_id=?", PLAYER_B);
        it.db.update("update play_team_member set status=0 where member_id=?", MATE_A);
        Object narrowed = broadcast("dc-bc-narrow", "ALL", null, null).getResult().get("broadcastId");
        assertEquals(new TreeSet<>(Set.of(PLAYER_A)), deliveredTo(narrowed), "退款/退队后只剩 A");

        // 范围内一个人都没有:报错且整笔回滚(广播行不留)。
        it.db.update("update play_team_member set status=0 where member_id=?", PLAYER_A);
        broadcasts = count("select count(*) from game_broadcast");
        revision = sessionRevision();
        assertEquals("GAME_BROADCAST_NO_RECIPIENT", assertThrows(GameSessionException.class,
                () -> broadcast("dc-bc-empty", "ALL", null, null)).getErrorCode());
        assertEquals(broadcasts, count("select count(*) from game_broadcast"), "无人可投时广播行随事务回滚");
        assertEquals(revision, sessionRevision());
    }

    @Test
    void sameRequestRetryResendsOnlyTheFailedDelivery() {
        // B 第一次投递失败 → PARTIAL;同 requestId 重试只补发 B,A 的已送达行(含 message_id)不动。
        when(im.sendSystemInNestedTransaction(eq(PLAYER_B), anyInt(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("im down")).thenReturn(424242L);
        GameCommandReceiptView first = broadcast("dc-bc-retry", "ALL", null, null);
        Object id = first.getResult().get("broadcastId");
        assertEquals("PARTIAL", it.db.queryForObject("select status from game_broadcast where id=?", String.class, id));
        assertEquals(1, count("select count(*) from game_broadcast_delivery where broadcast_id=? and status='FAILED'", id));
        List<Map<String, Object>> reachedBefore = it.db.queryForList(
                "select id,member_id,message_id,status from game_broadcast_delivery where broadcast_id=? and status='REACHED' order by id", id);
        assertEquals(2, reachedBefore.size());

        GameSessionCommand retry = command("dc-bc-retry", "BROADCAST", null);
        retry.setExpectedRevision(first.getRevision() - 1);
        retry.getPayload().put("targetType", "ALL");
        retry.getPayload().put("content", "集合点改到旧钟楼");
        it.runtime.command(GameActorContext.club(DIRECTOR), retry);
        assertEquals("SENT", it.db.queryForObject("select status from game_broadcast where id=?", String.class, id));
        assertEquals(reachedBefore, it.db.queryForList(
                "select id,member_id,message_id,status from game_broadcast_delivery where broadcast_id=? and member_id<>? order by id",
                id, PLAYER_B), "已送达的投递行原样不动");
        assertEquals(424242L, it.db.queryForObject(
                "select message_id from game_broadcast_delivery where broadcast_id=? and member_id=?", Long.class, id, PLAYER_B));
        verify(im, times(2)).sendSystemInNestedTransaction(eq(PLAYER_B), anyInt(), anyString(), anyString());
        verify(im, times(1)).sendSystemInNestedTransaction(eq(PLAYER_A), anyInt(), anyString(), anyString());
        assertEquals(1, count("select count(*) from game_broadcast"), "重试不新建广播");
    }

    // ———————————————————— D6 / M6 / P9 ————————————————————

    @Test
    @SuppressWarnings("unchecked")
    void pauseAndResumeTouchOnlyTheChosenStationAndPlayerReadsItBack() {
        Map<String, Object> clockBefore = it.db.queryForMap("select * from game_station_run where id=?", CLOCK_RUN);
        Map<String, Object> marketBefore = it.db.queryForMap("select * from game_station_run where id=?", MARKET_RUN);
        int revision = sessionRevision();

        // 别家商家不能暂停我的站点。
        GameSessionCommand foreign = command("dc-pause-foreign", "STATION_PAUSE", ARCHIVE_NODE);
        foreign.getPayload().put("reasonCode", "STAFF_ABSENT");
        foreign.getPayload().put("resumeEta", at(30 * 60_000L));
        assertEquals("GAME_FORBIDDEN_SCOPE", assertThrows(GameSessionException.class,
                () -> it.runtime.command(GameActorContext.merchant(CLOCK_MERCHANT), foreign)).getErrorCode());

        // M6 本站商家暂停:只动这一行(PAUSED / 原因 / 预计恢复 / 站点版本 +1),会话版本 +1。
        GameSessionCommand pause = command("dc-pause", "STATION_PAUSE", ARCHIVE_NODE);
        pause.getPayload().put("reasonCode", "EQUIPMENT");
        pause.getPayload().put("reason", "收银机故障");
        pause.getPayload().put("resumeEta", at(30 * 60_000L));
        assertEquals("APPLIED", it.runtime.command(GameActorContext.merchant(ARCHIVE_MERCHANT), pause).getOutcome());
        Map<String, Object> archive = it.db.queryForMap("select status,revision,pause_reason_code,resume_eta from game_station_run where id=?", ARCHIVE_RUN);
        assertEquals("PAUSED", archive.get("status"));
        assertEquals(1, ((Number) archive.get("revision")).intValue());
        assertEquals("EQUIPMENT", archive.get("pause_reason_code"));
        assertNotNull(archive.get("resume_eta"));
        assertEquals(clockBefore, it.db.queryForMap("select * from game_station_run where id=?", CLOCK_RUN), "邻站不动");
        assertEquals(marketBefore, it.db.queryForMap("select * from game_station_run where id=?", MARKET_RUN), "邻站不动");
        assertEquals(revision + 1, sessionRevision());

        // P9 玩家读回:暂停后刷新,本站显示暂停,会话版本与库一致。
        GameSessionView paused = it.runtime.view(GameActorContext.player(PLAYER_A), ACTIVITY);
        assertEquals(sessionRevision(), paused.getRevision());
        assertEquals("PAUSED", nodeStatus(paused, ARCHIVE_NODE), "玩家刷新后看到本站暂停");
        assertEquals("ACTIVE", nodeStatus(paused, CLOCK_NODE));

        // D6 第二终端(导演台)重复暂停:无变化,FAILED 回执,零版本变化。
        GameSessionCommand second = command("dc-pause-club", "CLUB_STATION_PAUSE", ARCHIVE_NODE);
        second.getPayload().put("reasonCode", "STAFF_ABSENT");
        second.getPayload().put("resumeEta", at(30 * 60_000L));
        GameSessionException noop = it.commandLikeController(GameActorContext.club(DIRECTOR), second);
        assertEquals(GameSessionException.STATION_ALREADY_PAUSED, noop.getErrorCode());
        assertEquals(revision + 1, sessionRevision(), "重复暂停不加版本");
        assertEquals("FAILED", it.db.queryForObject("select outcome from game_command_receipt where request_id='dc-pause-club'", String.class));

        // D6 导演台恢复:原因清空、ACTIVE,玩家刷新读回 ACTIVE。
        assertEquals("APPLIED", it.runtime.command(GameActorContext.club(DIRECTOR),
                command("dc-resume", "CLUB_STATION_RESUME", ARCHIVE_NODE)).getOutcome());
        Map<String, Object> resumed = it.db.queryForMap("select status,revision,pause_reason_code,resume_eta from game_station_run where id=?", ARCHIVE_RUN);
        assertEquals("ACTIVE", resumed.get("status"));
        assertEquals(2, ((Number) resumed.get("revision")).intValue());
        assertNull(resumed.get("pause_reason_code"));
        assertNull(resumed.get("resume_eta"));
        GameSessionView back = it.runtime.view(GameActorContext.player(PLAYER_A), ACTIVITY);
        assertEquals("ACTIVE", nodeStatus(back, ARCHIVE_NODE));
        assertEquals(sessionRevision(), back.getRevision());
        assertEquals(marketBefore, it.db.queryForMap("select * from game_station_run where id=?", MARKET_RUN));
    }

    @SuppressWarnings("unchecked")
    static String nodeStatus(GameSessionView view, long nodeId) {
        for (Map<String, Object> node : (List<Map<String, Object>>) view.getPlayer().get("nodes")) {
            if (((Number) node.get("nodeId")).longValue() == nodeId) return String.valueOf(node.get("status"));
        }
        throw new AssertionError("player view lacks node " + nodeId + ": " + view.getPlayer().get("nodes"));
    }

    // ———————————————————— P1 / P2 / P3 ————————————————————

    @Test
    @SuppressWarnings("unchecked")
    void playerViewIsScopedToOwnTeamAndOwnAccount() {
        it.arrive(PLAYER_A, ARCHIVE_NODE);
        assertEquals("APPLIED", it.submit(PLAYER_A, ARCHIVE_NODE, "ARCHIVE_TEXT", "1998").getOutcome());

        // P1 自己:读回自己的提交。
        GameSessionView mine = it.runtime.view(GameActorContext.player(PLAYER_A), ACTIVITY);
        List<Map<String, Object>> mySubmissions = (List<Map<String, Object>>) mine.getPlayer().get("mySubmissions");
        assertEquals(1, mySubmissions.size(), "提交人刷新后读回自己的提交:" + mine.getPlayer().keySet());
        assertEquals("PENDING", mySubmissions.get(0).get("status"));
        assertEquals("AWAITING_VERIFICATION", personalState(mine, ARCHIVE_NODE), "进度读回:本站待商家核验");

        // P3 同队另一个账号:看不到队友的提交明细(证据/答案)。
        GameSessionView mate = it.runtime.view(GameActorContext.player(MATE_A), ACTIVITY);
        List<Map<String, Object>> mateSubmissions = (List<Map<String, Object>>) mate.getPlayer().get("mySubmissions");
        assertTrue(mateSubmissions == null || mateSubmissions.isEmpty(), "同队别的账号不读到我的提交:" + mateSubmissions);
        assertFalse(String.valueOf(mate.getPlayer()).contains("text:1998"), "答案文本不出现在队友视图");

        // P2 别队:看不到 A 队的成员 / 提交 / 答案。
        GameSessionView other = it.runtime.view(GameActorContext.player(PLAYER_B), ACTIVITY);
        String otherText = String.valueOf(other.getPlayer());
        assertFalse(otherText.contains("text:1998"), "别队视图不含 A 的答案");
        List<Map<String, Object>> teamActions = (List<Map<String, Object>>) other.getPlayer().get("teamActions");
        assertFalse(teamActions.isEmpty());
        for (Map<String, Object> action : teamActions) {
            assertEquals(PLAYER_B, ((Number) action.get("memberId")).longValue(), "别队动作流只含 B 队成员:" + teamActions);
        }
        assertEquals(TEAM_B, ((Number) other.getPlayer().get("teamId")).longValue());
        List<Map<String, Object>> otherSubmissions = (List<Map<String, Object>>) other.getPlayer().get("mySubmissions");
        assertTrue(otherSubmissions == null || otherSubmissions.isEmpty());
        assertEquals("AVAILABLE", personalState(other, ARCHIVE_NODE), "别队的站点状态不受 A 的提交影响");

        // 负控:不是本场队员的账号读不到本局。
        assertEquals("GAME_FORBIDDEN_SCOPE", assertThrows(GameSessionException.class,
                () -> it.runtime.view(GameActorContext.player(OTHER_PLAYER), ACTIVITY)).getErrorCode());
        Set<String> keys = new HashSet<>(mine.getPlayer().keySet());
        System.out.println("P1-PLAYER readback: keys=" + keys + " mine=" + mySubmissions);
    }

    @SuppressWarnings("unchecked")
    static String personalState(GameSessionView view, long nodeId) {
        for (Map<String, Object> node : (List<Map<String, Object>>) view.getPlayer().get("nodes")) {
            if (((Number) node.get("nodeId")).longValue() == nodeId) return String.valueOf(node.get("personalState"));
        }
        throw new AssertionError("player view lacks node " + nodeId);
    }
}
