package com.chengyinhub.business.service.support.rpg.mechanism;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.JSON;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClueSecretGoalConsumerTest {
    @Test
    void gainingAClueWritesExistingStateKeyAndHiddenEvidenceIsMonotonic() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ClueSecretGoalConsumer clues = new ClueSecretGoalConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode gain = choice(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL, ClueSecretGoalConsumer.ACTION_GAIN_CLUE);
        gain.put("var", "clue.page");
        session.setPlayerChoice(gain);
        session.setOriginalRequestId("clue-1");
        Map<String, Object> held = clues.apply(session);
        assertEquals(1, ((Number) held.get("value")).intValue());

        session.getState().getVariables().put("clue.h_page", Integer.valueOf(1));
        ObjectNode down = MechanismRequestIdBinder.JSON.createObjectNode();
        down.put("op", "SET");
        down.put("var", "clue.h_page");
        down.put("value", 0);
        session.engine().applyEffects(MechanismRequestIdBinder.JSON.createArrayNode().add(down), session.getState());
        assertEquals(1, session.getState().getVariables().get("clue.h_page").intValue(), "clue.h_* 只增不减");
    }

    @Test
    void secretGoalOnlyReportsWhetherAuthorWhenPassesWithoutInventingRewards() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ClueSecretGoalConsumer clues = new ClueSecretGoalConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode check = choice(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL, ClueSecretGoalConsumer.ACTION_CHECK_GOAL);
        check.put("goalKey", "find_ledger");
        session.setPlayerChoice(check);
        session.setOriginalRequestId("goal-miss");
        Map<String, Object> miss = clues.apply(session);
        assertEquals(Boolean.FALSE, miss.get("met"));

        session.getState().getVariables().put("clue.page", Integer.valueOf(1));
        session.setOriginalRequestId("goal-hit");
        Map<String, Object> hit = clues.apply(session);
        assertEquals(Boolean.TRUE, hit.get("met"));
        assertTrue(!hit.containsKey("reward") && !hit.containsKey("amount"));
    }

    @Test
    void unconfirmedEconomyFieldsAreDecisionsNotInventedValues() throws Exception {
        ClueSecretGoalConsumer clues = new ClueSecretGoalConsumer(new MechanismRequestIdBinder());
        IllegalArgumentException faction = assertThrows(IllegalArgumentException.class,
                () -> clues.select(JSON.readTree("{\"secretGoals\":[{\"key\":\"x\",\"label\":\"x\","
                        + "\"when\":{\"var\":\"clue.page\",\"op\":\"EQ\",\"value\":1},\"factionReward\":10}]}")));
        assertTrue(faction.getMessage().contains("经济值未确认"));
        IllegalArgumentException territory = assertThrows(IllegalArgumentException.class,
                () -> clues.select(JSON.readTree("{\"territory\":{\"hp\":5}}")));
        assertTrue(territory.getMessage().contains("经济值未确认") || territory.getMessage().contains("不支持"));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueGainClueApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ClueSecretGoalConsumer clues = new ClueSecretGoalConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-clue-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = clues.recoverOriginal(unseen);
        assertEquals("c03-clue-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-clue-never"));
        ObjectNode gain = choice(MechanismRequestIdBinder.MECH_CLUE_SECRET_GOAL, ClueSecretGoalConsumer.ACTION_GAIN_CLUE);
        gain.put("var", "clue.page");
        unseen.setPlayerChoice(gain);
        Map<String, Object> first = clues.apply(unseen);
        assertEquals("c03-clue-never", first.get("requestId"));
        assertEquals(1, ((Number) first.get("value")).intValue());
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-clue-never").getStatus());
        Map<String, Object> replay = clues.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-clue-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-clue-never").getStatus());
    }
}
