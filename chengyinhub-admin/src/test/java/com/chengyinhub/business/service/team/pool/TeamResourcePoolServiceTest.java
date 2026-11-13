package com.chengyinhub.business.service.team.pool;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.mapper.team.pool.TeamResourcePoolMapper;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TeamResourcePoolServiceTest
{
    static final long TEAM = 7, A = 21, B = 22, OUTSIDER = 99, TOPIC = 300, NODE = 4001;
    static final String CONFIG = "{\"gameplayRuntime\":{\"teamPool\":{\"hints\":2,\"skips\":1,\"timerSec\":600}}}";

    TeamResourcePoolMapper mapper;
    TeamResourcePoolService service;
    Map<String, Object> pool;
    Map<String, Map<String, Object>> receipts = new HashMap<>();
    Map<String, Object> use;

    @BeforeEach void setUp()
    {
        mapper = mock(TeamResourcePoolMapper.class);
        service = new TeamResourcePoolService(mapper);
        when(mapper.selectTeam(TEAM)).thenReturn(Map.of("ownerType", 1, "ownerId", TOPIC, "leaderId", A, "status", 2));
        when(mapper.countActiveMember(eq(TEAM), anyLong())).thenAnswer(i -> (long) i.getArgument(1) == OUTSIDER ? 0 : 1);
        when(mapper.selectPublishedTheme(TOPIC)).thenReturn(CONFIG);
        when(mapper.countListedNode(TOPIC, NODE)).thenReturn(1);
        when(mapper.selectSolvedNodes(TEAM, TOPIC, 0L)).thenReturn(List.of(4001L, 4002L));
        when(mapper.insertPool(anyMap())).thenAnswer(i -> {
            Map<String, Object> row = i.getArgument(0);
            pool = new HashMap<>(Map.of("teamId", TEAM, "topicId", TOPIC, "activityId", 0L, "hintsTotal", row.get("hintsTotal"),
                    "hintsUsed", 0, "skipsTotal", row.get("skipsTotal"), "skipsUsed", 0, "timerLimitSec", row.get("timerLimitSec"),
                    "version", 0));
            return 1;
        });
        when(mapper.selectPool(TEAM)).thenAnswer(i -> pool);
        when(mapper.lockPool(TEAM)).thenAnswer(i -> pool);
        when(mapper.consume(eq(TEAM), anyString())).thenAnswer(i -> {
            String used = "HINT".equals(i.getArgument(1)) ? "hintsUsed" : "skipsUsed";
            String total = "HINT".equals(i.getArgument(1)) ? "hintsTotal" : "skipsTotal";
            if (((Number) pool.get(used)).intValue() >= ((Number) pool.get(total)).intValue()) return 0;
            pool.put(used, ((Number) pool.get(used)).intValue() + 1);
            return 1;
        });
        when(mapper.selectUse(eq(TEAM), anyString(), anyLong())).thenAnswer(i -> use);
        when(mapper.insertUse(eq(TEAM), anyString(), anyLong(), anyLong(), anyString())).thenAnswer(i -> {
            use = Map.of("memberId", i.getArgument(3), "requestId", i.getArgument(4));
            return 1;
        });
        when(mapper.reserveReceipt(anyLong(), anyString(), anyLong(), anyString())).thenAnswer(i -> {
            receipts.putIfAbsent(i.getArgument(0) + "|" + i.getArgument(1),
                    new HashMap<>(Map.of("teamId", i.getArgument(2), "fingerprint", i.getArgument(3))));
            return 1;
        });
        when(mapper.lockReceipt(anyLong(), anyString())).thenAnswer(i -> receipts.get(i.getArgument(0) + "|" + i.getArgument(1)));
        when(mapper.selectReceipt(anyLong(), anyString())).thenAnswer(i -> receipts.get(i.getArgument(0) + "|" + i.getArgument(1)));
        when(mapper.finishReceipt(anyLong(), anyString(), anyString())).thenAnswer(i -> {
            receipts.get(i.getArgument(0) + "|" + i.getArgument(1)).put("response", i.getArgument(2));
            return 1;
        });
    }

    static Map<String, Object> body(Object... kv)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String code(Runnable r) { return assertThrows(ChannelRejection.class, r::run).getErrorCode(); }

    @Test void outsiderCannotReadOrSpend()
    {
        assertEquals("TEAM_NOT_MEMBER", code(() -> service.pool(OUTSIDER, TEAM)));
        assertEquals("TEAM_NOT_MEMBER", code(() -> service.consume(OUTSIDER, "HINT",
                body("teamId", TEAM, "nodeId", NODE, "requestId", "pool-request-000001"))));
    }

    @Test void poolIsDisabledWithoutPublishedConfig()
    {
        when(mapper.selectPublishedTheme(TOPIC)).thenReturn("{\"gameplayRuntime\":{}}");
        Map<String, Object> view = service.pool(A, TEAM);
        assertEquals(false, view.get("enabled"));
        assertEquals("TEAM_POOL_NOT_CONFIGURED", view.get("unavailableReason"));
        verify(mapper, never()).insertPool(anyMap());
    }

    @Test void limitsFreezeAndTeamScoreComesFromFacts()
    {
        Map<String, Object> view = service.pool(A, TEAM);
        assertEquals(true, view.get("enabled"));
        assertEquals(2L, view.get("hintsLeft"));
        assertEquals(2, view.get("teamScore"));
        assertEquals("TEAM_FIRST_SOLVE_NODES", view.get("scoreBasis"));
        when(mapper.selectPublishedTheme(TOPIC)).thenReturn("{\"gameplayRuntime\":{\"teamPool\":{\"hints\":9}}}");
        assertEquals(2L, service.pool(B, TEAM).get("hintsTotal"), "existing pool keeps its frozen limits");
    }

    @Test void sameNodeHintIsSpentOnceForTheWholeTeam()
    {
        Map<String, Object> first = service.consume(A, "HINT", body("teamId", TEAM, "nodeId", NODE, "requestId", "pool-request-000002"));
        assertEquals(true, first.get("consumed"));
        Map<String, Object> second = service.consume(B, "HINT", body("teamId", TEAM, "nodeId", NODE, "requestId", "pool-request-000003"));
        assertEquals(false, second.get("consumed"));
        assertEquals(((Number) first.get("byMemberId")).longValue(), ((Number) second.get("byMemberId")).longValue());
        assertEquals(1, ((Number) pool.get("hintsUsed")).intValue());
    }

    @Test void exhaustedSkipAndReplayAndConflict()
    {
        service.pool(A, TEAM);
        when(mapper.countListedNode(TOPIC, 4002L)).thenReturn(1);
        Map<String, Object> first = service.consume(A, "SKIP", body("teamId", TEAM, "nodeId", NODE, "requestId", "pool-request-000004"));
        Map<String, Object> replay = service.consume(A, "SKIP", body("teamId", TEAM, "nodeId", NODE, "requestId", "pool-request-000004"));
        assertEquals(true, replay.get("replayed"));
        assertEquals(first.get("nodeId"), replay.get("nodeId"));
        use = null;
        assertEquals("TEAM_POOL_EXHAUSTED", code(() -> service.consume(A, "SKIP",
                body("teamId", TEAM, "nodeId", 4002L, "requestId", "pool-request-000005"))));
        assertEquals("REQUEST_ID_CONFLICT", code(() -> service.consume(A, "SKIP",
                body("teamId", TEAM, "nodeId", 4002L, "requestId", "pool-request-000004"))));
    }

    @Test void limitsParserIgnoresMalformedConfig()
    {
        assertNull(TeamResourcePoolService.limits(null));
        assertNull(TeamResourcePoolService.limits("not json"));
        assertNull(TeamResourcePoolService.limits("{\"gameplayRuntime\":{\"teamPool\":{\"hints\":\"x\"}}}"));
        assertEquals(new TeamResourcePoolService.Limits(2, 1, 600), TeamResourcePoolService.limits(CONFIG));
    }
}
