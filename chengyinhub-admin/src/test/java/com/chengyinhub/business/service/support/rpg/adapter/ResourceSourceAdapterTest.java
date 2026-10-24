package com.chengyinhub.business.service.support.rpg.adapter;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IGameSessionRuntimeService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.BEFORE;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.REQUEST;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.ctx;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.dice;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.enableInventory;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.rules;
import static com.chengyinhub.business.service.support.rpg.adapter.RpgAdapterFixtures.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceSourceAdapterTest {

    @Test
    void originalAddItemAndGrantXpAreIdempotentOnSameRequestId() throws Exception {
        JourneyRules journey = rules(null);
        JsonNode check = JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}");
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree("["
                + "{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1},"
                + "{\"op\":\"GRANT_XP\",\"value\":5,\"id\":\"page\"}]"));
        TopicRouteEngine.State run = state();
        enableInventory(run);
        RpgAdapterContext context = ctx(journey, run, check, spec, BEFORE, dice(12));
        context.setWriteResources(true);
        RpgAdapterPipeline pipeline = new RpgAdapterPipeline(null, BEFORE);
        PlayCheckReceipt first = pipeline.roll(context);
        assertTrue(first.getSuccess());
        assertEquals(REQUEST, first.getBreakdown().get("requestId"));
        String source = "story:1:clue_page";
        assertTrue(run.getThemeRuntime().path("sources").path(source).asBoolean());
        assertEquals(1, run.getThemeRuntime().path("pending").path(source).path("quantity").asInt());
        assertEquals(1, run.getStoryGrants().size(), "GRANT_XP 走原 storyGrants,不是第二钱包");

        java.util.Map<String, Object> replay = pipeline.resources().writeOriginal(context);
        assertEquals(1, run.getThemeRuntime().path("pending").size(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getStoryGrants().size());
        assertEquals(REQUEST, first.getBreakdown().get("requestId"));
        assertEquals(0, ((Number) replay.get("sourcesDelta")).intValue());
        assertEquals(0, ((Number) replay.get("grantsDelta")).intValue());
    }

    @Test
    void frozenSpecDoesNotInitializeInventoryOrWriteGrants() throws Exception {
        TopicRouteEngine.State state = state();
        RpgAdapterContext context = ctx(rules(null), state,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                RpgAdapterSpec.inactive(), BEFORE, dice(12));
        context.setWriteResources(true);
        PlayCheckReceipt roll = new RpgAdapterPipeline(null, BEFORE).roll(context);
        assertTrue(state.getThemeRuntime().isEmpty() || !state.getThemeRuntime().has("pending"));
        assertTrue(state.getStoryGrants().isEmpty());
        assertFalse(roll.getBreakdown().containsKey("resources"));
    }

    @Test
    void emptyEffectsOfPresentNeverSeenIdDoesNotOccupyStorySourceThenUniqueWriteApplies() throws Exception {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree("[]"));
        RpgAdapterContext unseen = ctx(rules(null), run,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                spec, BEFORE, dice(12));
        unseen.setWriteResources(true);
        unseen.setOriginalRequestId("c02-resource-class");
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setSuccess(Boolean.TRUE);
        unseen.setReceipt(receipt);

        ResourceSourceAdapter adapter = new ResourceSourceAdapter();
        Map<String, Object> empty = adapter.writeOriginal(unseen);
        assertEquals(Boolean.FALSE, empty.get("applied"));
        assertEquals(Boolean.TRUE, empty.get("emptyEffects"));
        assertEquals("c02-resource-class", empty.get("requestId"));
        assertFalse(run.getThemeRuntime().path("sources").has("story:1:clue_page"),
                "空效果观察不得占 story:1:clue_page");
        assertTrue(run.getStoryGrants().isEmpty());

        spec.getResources().setSuccessEffects(JSON.readTree(
                "[{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1}]"));
        Map<String, Object> first = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, first.get("applied"));
        assertEquals("c02-resource-class", first.get("requestId"));
        assertEquals(1, ((Number) first.get("sourcesDelta")).intValue());
        assertTrue(run.getThemeRuntime().path("sources").path("story:1:clue_page").asBoolean());

        Map<String, Object> replay = adapter.writeOriginal(unseen);
        assertEquals(Boolean.TRUE, replay.get("applied"));
        assertEquals("c02-resource-class", replay.get("requestId"));
        assertEquals(0, ((Number) replay.get("sourcesDelta")).intValue(), "同一 originalRequestId 不二发物资");
        assertEquals(1, run.getThemeRuntime().path("sources").size());
        assertEquals(Boolean.FALSE, first.get("persisted"));
        assertEquals(ResourceSourceAdapter.RUNTIME_PERSIST, first.get("waitingCapability"));
    }

    @Test
    void persistOriginalRequiresRuntimeAndNewInstanceReadsSameReceipt() throws Exception {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        RpgAdapterSpec spec = new RpgAdapterSpec();
        spec.setResources(new RpgAdapterSpec.Resources());
        spec.getResources().setSuccessEffects(JSON.readTree(
                "[{\"op\":\"ADD_ITEM\",\"itemId\":\"clue_page\",\"quantity\":1}]"));
        RpgAdapterContext context = ctx(rules(null), run,
                JSON.readTree("{\"enabled\":true,\"checkId\":\"ask_arong\",\"tier\":\"easy\"}"),
                spec, BEFORE, dice(12));
        context.setWriteResources(true);
        PlayCheckReceipt receipt = new PlayCheckReceipt();
        receipt.setSuccess(Boolean.TRUE);
        context.setReceipt(receipt);

        ResourceSourceAdapter missing = new ResourceSourceAdapter();
        ResourceSourceAdapter.WaitingSourceException waiting = assertThrows(
                ResourceSourceAdapter.WaitingSourceException.class,
                () -> missing.persistOriginal(context));
        assertEquals(ResourceSourceAdapter.RUNTIME_PERSIST, waiting.getWaitingCapability());
        assertNull(missing.recoverFromPersist(null));

        Map<String, ResourceSourceAdapter.OriginalReceipt> ledger =
                new ConcurrentHashMap<String, ResourceSourceAdapter.OriginalReceipt>();
        ResourceSourceAdapter.OriginalResourcePersist persist =
                new ResourceSourceAdapter.OriginalResourcePersist() {
                    @Override
                    public ResourceSourceAdapter.OriginalReceipt persist(
                            String requestId, TopicRouteEngine.State snapshot) {
                        ResourceSourceAdapter.OriginalReceipt existing = ledger.get(requestId);
                        if (existing != null) {
                            return new ResourceSourceAdapter.OriginalReceipt(requestId, true);
                        }
                        ResourceSourceAdapter.OriginalReceipt written =
                                new ResourceSourceAdapter.OriginalReceipt(requestId, false);
                        ledger.put(requestId, written);
                        return written;
                    }

                    @Override
                    public ResourceSourceAdapter.OriginalReceipt recover(String requestId) {
                        return ledger.get(requestId);
                    }
                };
        ResourceSourceAdapter wired = new ResourceSourceAdapter(persist);
        ResourceSourceAdapter.OriginalReceipt first = wired.persistOriginal(context);
        assertEquals(REQUEST, first.requestId);
        assertFalse(first.replay);
        ResourceSourceAdapter restarted = new ResourceSourceAdapter(persist);
        ResourceSourceAdapter.OriginalReceipt recovered = restarted.recoverFromPersist(REQUEST);
        assertEquals(REQUEST, recovered.requestId);
        assertFalse(recovered.replay);
        assertNull(restarted.recoverFromPersist("c02-persist-missing"));
        ResourceSourceAdapter.OriginalResourcePersist swapped =
                new ResourceSourceAdapter.OriginalResourcePersist() {
                    @Override
                    public ResourceSourceAdapter.OriginalReceipt persist(
                            String requestId, TopicRouteEngine.State snapshot) {
                        throw new AssertionError("recover must not persist");
                    }

                    @Override
                    public ResourceSourceAdapter.OriginalReceipt recover(String requestId) {
                        return new ResourceSourceAdapter.OriginalReceipt("other-id", true);
                    }
                };
        assertNull(new ResourceSourceAdapter(swapped).recoverFromPersist(REQUEST));
    }

    @Test
    void recoverFromRouteActionReadsExistingMapperRowWithoutOccupyingSources() throws Exception {
        TopicRouteEngine.State run = state();
        enableInventory(run);
        int sourcesBefore = run.getThemeRuntime().path("sources").size();

        ResourceSourceAdapter missing = new ResourceSourceAdapter();
        ResourceSourceAdapter.WaitingSourceException waiting = assertThrows(
                ResourceSourceAdapter.WaitingSourceException.class,
                () -> missing.recoverFromRouteAction(30L, 7L, "ask:40:why"));
        assertEquals(ResourceSourceAdapter.RPG_REQUEST_RECEIPTS, waiting.getWaitingCapability());
        assertNull(missing.recoverFromRouteAction(null, 7L, "ask:40:why"));
        assertNull(missing.recoverFromRouteAction(30L, null, "ask:40:why"));
        assertNull(missing.recoverFromRouteAction(30L, 7L, null));
        assertNull(missing.recoverFromRouteAction(30L, 7L, "   "));
        assertEquals(sourcesBefore, run.getThemeRuntime().path("sources").size());

        Map<String, TopicRouteAction> rows = new ConcurrentHashMap<String, TopicRouteAction>();
        TopicRouteAction stored = new TopicRouteAction();
        stored.setSessionId(30L);
        stored.setMemberId(7L);
        stored.setActionId("ask:40:why");
        rows.put("30:7:ask:40:why", stored);
        ResourceSourceAdapter.OriginalRouteActionSource source =
                new ResourceSourceAdapter.OriginalRouteActionSource() {
                    @Override
                    public TopicRouteAction selectActionByAction(Long sessionId, Long memberId, String actionId) {
                        return rows.get(sessionId + ":" + memberId + ":" + actionId);
                    }
                };
        ResourceSourceAdapter wired = new ResourceSourceAdapter(null, source);
        assertNull(wired.recoverFromRouteAction(30L, 7L, "c02-route-missing"));
        assertEquals(sourcesBefore, run.getThemeRuntime().path("sources").size(),
                "未见过的 actionId 不得占 sources");

        ResourceSourceAdapter restarted = new ResourceSourceAdapter(null, source);
        ResourceSourceAdapter.OriginalReceipt recovered =
                restarted.recoverFromRouteAction(30L, 7L, "ask:40:why");
        assertEquals("ask:40:why", recovered.requestId);
        assertTrue(recovered.replay);

        TopicRouteAction swapped = new TopicRouteAction();
        swapped.setSessionId(30L);
        swapped.setMemberId(7L);
        swapped.setActionId("other-action");
        rows.put("30:7:c02-swapped", swapped);
        assertNull(restarted.recoverFromRouteAction(30L, 7L, "c02-swapped"));

        TopicRouteAction otherSession = new TopicRouteAction();
        otherSession.setSessionId(99L);
        otherSession.setMemberId(7L);
        otherSession.setActionId("ask:40:other-session");
        rows.put("30:7:ask:40:other-session", otherSession);
        assertNull(restarted.recoverFromRouteAction(30L, 7L, "ask:40:other-session"));

        TopicRouteAction otherMember = new TopicRouteAction();
        otherMember.setSessionId(30L);
        otherMember.setMemberId(8L);
        otherMember.setActionId("ask:40:other-member");
        rows.put("30:7:ask:40:other-member", otherMember);
        assertNull(restarted.recoverFromRouteAction(30L, 7L, "ask:40:other-member"));

        TopicRouteAction blankAction = new TopicRouteAction();
        blankAction.setSessionId(30L);
        blankAction.setMemberId(7L);
        rows.put("30:7:c02-blank-action", blankAction);
        assertNull(restarted.recoverFromRouteAction(30L, 7L, "c02-blank-action"));
        assertEquals(sourcesBefore, run.getThemeRuntime().path("sources").size());
    }

    @Test
    void recoverFromGameReceiptReadsPendingWithoutApplyingOrCommand() {
        ResourceSourceAdapter adapter = new ResourceSourceAdapter();
        GameActorContext actor = new GameActorContext(7L, "PLAYER");
        ResourceSourceAdapter.WaitingSourceException waiting = assertThrows(
                ResourceSourceAdapter.WaitingSourceException.class,
                () -> adapter.recoverFromGameReceipt(actor, 11L, "game-req-01", null));
        assertEquals(ResourceSourceAdapter.GAME_RECEIPT, waiting.getWaitingCapability());
        assertNull(adapter.recoverFromGameReceipt(null, 11L, "game-req-01", (a, activityId, requestId) -> {
            throw new AssertionError("invalid actor must not read");
        }));
        ResourceSourceAdapter.WaitingSourceException missingRuntime = assertThrows(
                ResourceSourceAdapter.WaitingSourceException.class,
                () -> ResourceSourceAdapter.fromGameRuntime(null).receipt(actor, 11L, "game-req-01"));
        assertEquals(ResourceSourceAdapter.GAME_RECEIPT, missingRuntime.getWaitingCapability());

        AtomicInteger reads = new AtomicInteger();
        ResourceSourceAdapter.GameReceiptSource source = (a, activityId, requestId) -> {
            reads.incrementAndGet();
            GameCommandReceiptView view = new GameCommandReceiptView();
            view.setActivityId("c02-game-activity".equals(requestId) ? Long.valueOf(99L) : activityId);
            view.setRequestId("c02-game-other".equals(requestId) ? "other-request" : requestId);
            view.setOutcome("c02-game-missing".equals(requestId) ? "PENDING" : "APPLIED");
            view.setReplayed(!"c02-game-missing".equals(requestId));
            return view;
        };
        assertNull(adapter.recoverFromGameReceipt(actor, 11L, "c02-game-other", source));
        assertNull(adapter.recoverFromGameReceipt(actor, 11L, "c02-game-activity", source));
        ResourceSourceAdapter.GameReceiptRead pending = adapter.recoverFromGameReceipt(
                actor, 11L, "c02-game-missing", source);
        assertEquals("PENDING", pending.outcome);
        assertFalse(pending.applied);
        assertFalse(pending.replayed);
        ResourceSourceAdapter.GameReceiptRead applied = adapter.recoverFromGameReceipt(
                actor, 11L, "game-req-01", source);
        assertTrue(applied.applied);
        assertTrue(applied.replayed);
        assertEquals(4, reads.get());
    }

    @Test
    void pipelineReadsInjectedRouteActionAndGameReceiptWithoutASecondWriter() {
        RpgAdapterPipeline bare = new RpgAdapterPipeline(null, Clock.systemUTC());
        ResourceSourceAdapter.WaitingSourceException waiting = assertThrows(
                ResourceSourceAdapter.WaitingSourceException.class,
                () -> bare.recoverOriginalGame(new GameActorContext(7L, "PLAYER"), 11L, "game-req-01"));
        assertEquals(ResourceSourceAdapter.GAME_RECEIPT, waiting.getWaitingCapability());

        TopicRouteSessionMapper mapper = mock(TopicRouteSessionMapper.class);
        TopicRouteAction row = new TopicRouteAction();
        row.setSessionId(30L);
        row.setMemberId(7L);
        row.setActionId("ask:40:why");
        when(mapper.selectActionByAction(30L, 7L, "ask:40:why")).thenReturn(row);
        IGameSessionRuntimeService games = mock(IGameSessionRuntimeService.class);
        GameCommandReceiptView view = new GameCommandReceiptView();
        view.setActivityId(11L);
        view.setRequestId("game-req-01");
        view.setOutcome("APPLIED");
        view.setReplayed(true);
        when(games.receipt(any(GameActorContext.class), eq(11L), eq("game-req-01"))).thenReturn(view);
        RpgAdapterPipeline wired = new RpgAdapterPipeline(null, Clock.systemUTC(), mapper, games);
        ResourceSourceAdapter.OriginalReceipt action = wired.resources().recoverFromRouteAction(30L, 7L, "ask:40:why");
        assertEquals("ask:40:why", action.requestId);
        assertTrue(action.replay);
        ResourceSourceAdapter.GameReceiptRead game = wired.recoverOriginalGame(
                new GameActorContext(7L, "PLAYER"), 11L, "game-req-01");
        assertTrue(game.applied);
        assertEquals("game-req-01", game.requestId);
    }
}
