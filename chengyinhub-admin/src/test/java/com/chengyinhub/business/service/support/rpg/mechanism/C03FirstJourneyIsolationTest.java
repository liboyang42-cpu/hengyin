package com.chengyinhub.business.service.support.rpg.mechanism;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.REQUEST;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首个可跑旅程:作者选配已启用机制 → 校验/模拟 → 发布冻结 → 玩家显式选择
 * → 服务端按原 requestId 落一次效果 → 同 id 回读;UNKNOWN 不 mint 新请求。
 */
class C03FirstJourneyIsolationTest {
    @Test
    void authorSelectFreezeExplicitChooseOneShotReadbackAndUnknownKeepsOriginalRequestId() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK).path("enabled").asBoolean());
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_TRADE_BARGAIN).path("enabled").asBoolean());
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_ORACLE_BOUNTY).path("enabled").asBoolean());
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_OATH_FLASHBACK).path("enabled").asBoolean());
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_ROUTE_COMMUNITY_DAY).path("enabled").asBoolean());
        assertTrue(session.getFrozen().path(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL).path("enabled").asBoolean());

        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        session.setPlayerChoice(tick);
        Map<String, Object> simulated = clocks.simulate(session);
        assertEquals(Boolean.TRUE, simulated.get("simulated"));
        assertEquals(1, ((Number) simulated.get("value")).intValue());
        assertEquals(null, session.getState().getVariables().get("counter.alarm"), "模拟不得写原局");

        Map<String, Object> applied = clocks.apply(session);
        assertEquals(REQUEST, applied.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, applied.get("status"));
        assertEquals(Boolean.FALSE, applied.get("mintedNewRequest"));
        assertEquals(1, ((Number) applied.get("value")).intValue());
        assertEquals(1, session.getState().getVariables().get("counter.alarm").intValue());

        Map<String, Object> readback = binder.readback(REQUEST);
        assertEquals(REQUEST, readback.get("requestId"));
        assertEquals(Boolean.TRUE, readback.get("replay"));
        assertEquals(1, ((Number) readback.get("value")).intValue());

        Map<String, Object> replay = clocks.apply(session);
        assertEquals(REQUEST, replay.get("requestId"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals(1, session.getState().getVariables().get("counter.alarm").intValue(), "同 requestId 不得再推进钟");

        TopicRouteAction original = session.getOriginalAction();
        assertEquals(REQUEST, original.getActionId());
        assertEquals(REQUEST, original.getReceiptId());

        MechanismRequestIdBinder crashed = new MechanismRequestIdBinder();
        crashed.markUnknown(REQUEST);
        MechanismRequestIdBinder.Play recover = play(crashed);
        recover.setOriginalRequestId(REQUEST);
        Map<String, Object> stillUnknown = crashed.recoverUnknown(recover);
        assertEquals(REQUEST, stillUnknown.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, stillUnknown.get("status"));
        assertEquals(Boolean.FALSE, stillUnknown.get("mintedNewRequest"));
        assertNotEquals("retry:" + REQUEST, stillUnknown.get("requestId"));

        recover.setOriginalAction(original);
        Map<String, Object> restored = crashed.recoverUnknown(recover);
        assertEquals(REQUEST, restored.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, restored.get("status"));
        assertEquals(Boolean.TRUE, restored.get("replay"));
        assertEquals(Boolean.FALSE, restored.get("mintedNewRequest"));
        assertEquals(1, ((Number) restored.get("value")).intValue());

        TopicRouteAction minted = new TopicRouteAction();
        minted.setActionId("retry:" + REQUEST);
        minted.setResponseJson(original.getResponseJson());
        recover.setOriginalAction(minted);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> crashed.recoverUnknown(recover));
        assertTrue(refused.getMessage().contains("不得 mint"));
    }

    @Test
    void missingExplicitChoiceDoesNotDefaultApply() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        assertThrows(IllegalArgumentException.class, () -> clocks.apply(session));
        assertFalse(session.getState().getVariables().containsKey("counter.alarm"));
    }
}
