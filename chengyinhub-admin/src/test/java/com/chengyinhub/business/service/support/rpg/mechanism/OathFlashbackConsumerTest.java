package com.chengyinhub.business.service.support.rpg.mechanism;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OathFlashbackConsumerTest {
    @Test
    void swearAddsAuthorOathCondition() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OathFlashbackConsumer oath = new OathFlashbackConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode swear = choice(MechanismRequestIdBinder.MECH_OATH_FLASHBACK, OathFlashbackConsumer.ACTION_SWEAR);
        swear.put("oathKey", "keep_silent");
        session.setPlayerChoice(swear);
        session.setOriginalRequestId("oath-1");
        Map<String, Object> sworn = oath.apply(session);
        assertEquals(Boolean.TRUE, sworn.get("active"));
        assertTrue(session.getState().getStatusConditions().contains("keep_silent"));
    }

    @Test
    void flashbackRestoresFrozenKeysUsingAuthorConfiguredPassword() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OathFlashbackConsumer oath = new OathFlashbackConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        session.getState().getVariables().put("clue.page", Integer.valueOf(0));
        ObjectNode frozenOath = (ObjectNode) session.getFrozen().path(MechanismRequestIdBinder.MECH_OATH_FLASHBACK);
        session.getFrozen().set(MechanismRequestIdBinder.MECH_OATH_FLASHBACK,
                oath.freezeWithSnapshot(frozenOath, session.getState()));
        session.getState().getVariables().put("clue.page", Integer.valueOf(9));
        ObjectNode flash = choice(MechanismRequestIdBinder.MECH_OATH_FLASHBACK, OathFlashbackConsumer.ACTION_FLASHBACK);
        flash.put("flashbackId", "alley");
        flash.put("password", "author-set-cipher");
        session.setPlayerChoice(flash);
        session.setOriginalRequestId("flash-1");
        Map<String, Object> restored = oath.apply(session);
        assertEquals(Boolean.TRUE, restored.get("passwordChecked"));
        assertEquals(0, session.getState().getVariables().get("clue.page").intValue());
    }

    @Test
    void wrongPasswordDoesNotInventAGlobalAnswer() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OathFlashbackConsumer oath = new OathFlashbackConsumer(binder);
        MechanismRequestIdBinder.Play session = play(binder);
        ObjectNode flash = choice(MechanismRequestIdBinder.MECH_OATH_FLASHBACK, OathFlashbackConsumer.ACTION_FLASHBACK);
        flash.put("flashbackId", "alley");
        flash.put("password", "hunter2");
        session.setPlayerChoice(flash);
        session.setOriginalRequestId("flash-bad");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> oath.apply(session));
        assertTrue(refused.getMessage().contains("作者配置"));
        session.getState().getVariables().put("clue.page", Integer.valueOf(9));
        assertEquals(9, session.getState().getVariables().get("clue.page").intValue());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueSwearApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        OathFlashbackConsumer oath = new OathFlashbackConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-oath-never");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = oath.recoverOriginal(unseen);
        assertEquals("c03-oath-never", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-oath-never"));
        ObjectNode swear = choice(MechanismRequestIdBinder.MECH_OATH_FLASHBACK, OathFlashbackConsumer.ACTION_SWEAR);
        swear.put("oathKey", "keep_silent");
        unseen.setPlayerChoice(swear);
        Map<String, Object> first = oath.apply(unseen);
        assertEquals("c03-oath-never", first.get("requestId"));
        assertEquals(Boolean.TRUE, first.get("active"));
        assertEquals(Boolean.FALSE, first.get("replay"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-oath-never").getStatus());
        Map<String, Object> replay = oath.apply(unseen);
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals("c03-oath-never", replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-oath-never").getStatus());
    }
}
