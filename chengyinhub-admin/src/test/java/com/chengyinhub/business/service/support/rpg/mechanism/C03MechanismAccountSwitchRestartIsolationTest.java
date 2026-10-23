package com.chengyinhub.business.service.support.rpg.mechanism;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.REQUEST;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.choice;
import static com.chengyinhub.business.service.support.rpg.mechanism.MechanismFixtures.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * C12 isolation: another request cannot rewrite the original mechanism
 * effect; in-process recover keeps original APPLIED/UNKNOWN; missing
 * requestId is refused and never stored as {@code ""}.
 */
class C03MechanismAccountSwitchRestartIsolationTest {

    private static final String OTHER = "check:9:other:tick";

    @Test
    void otherRequestDoesNotRewriteOriginalClock() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play original = play(binder);
        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        original.setPlayerChoice(tick);
        Map<String, Object> applied = clocks.apply(original);
        assertEquals(REQUEST, applied.get("requestId"));
        assertEquals(1, ((Number) applied.get("value")).intValue());

        MechanismRequestIdBinder.Play steal = play(binder);
        steal.setOriginalRequestId(REQUEST);
        steal.setPlayerChoice(tick);
        steal.getState().getVariables().put("counter.alarm", 1);
        Map<String, Object> replay = clocks.apply(steal);
        assertEquals(REQUEST, replay.get("requestId"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals(1, ((Number) replay.get("value")).intValue());
        assertEquals(1, steal.getState().getVariables().get("counter.alarm").intValue());

        MechanismRequestIdBinder.Play independent = play(binder);
        independent.setOriginalRequestId(OTHER);
        independent.setPlayerChoice(tick);
        Map<String, Object> other = clocks.apply(independent);
        assertEquals(OTHER, other.get("requestId"));
        assertNotEquals(REQUEST, other.get("requestId"));
        assertEquals(1, ((Number) other.get("value")).intValue());
        Map<String, Object> originalReadback = binder.readback(REQUEST);
        assertEquals(REQUEST, originalReadback.get("requestId"));
        assertEquals(1, ((Number) originalReadback.get("value")).intValue());
        assertEquals(REQUEST, binder.existing(REQUEST).getRequestId());
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing(REQUEST).getStatus());
    }

    @Test
    void inProcessRecoverKeepsOriginalAppliedAndUnknown() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play original = play(binder);
        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        original.setPlayerChoice(tick);
        clocks.apply(original);

        MechanismRequestIdBinder recovered = new MechanismRequestIdBinder();
        recovered.completeApplied(REQUEST, binder.readback(REQUEST), original.getOriginalAction());
        Map<String, Object> replay = recovered.readback(REQUEST);
        assertEquals(REQUEST, replay.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, replay.get("status"));
        assertEquals(Boolean.TRUE, replay.get("replay"));
        assertEquals(Boolean.FALSE, replay.get("mintedNewRequest"));
        assertEquals(1, ((Number) replay.get("value")).intValue());

        MechanismRequestIdBinder unknownBinder = new MechanismRequestIdBinder();
        unknownBinder.markUnknown(REQUEST + "-unk");
        MechanismRequestIdBinder.Play unknownPlay = play(unknownBinder);
        unknownPlay.setOriginalRequestId(REQUEST + "-unk");
        Map<String, Object> unknown = unknownBinder.recoverUnknown(unknownPlay);
        assertEquals(REQUEST + "-unk", unknown.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, unknown.get("status"));
        assertEquals(Boolean.FALSE, unknown.get("mintedNewRequest"));
        assertNotEquals("retry:" + REQUEST + "-unk", unknown.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, recovered.readback(REQUEST).get("status"));
    }

    @Test
    void missingRequestIdIsRefusedNeverStoredAsEmptyString() {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        assertThrows(IllegalArgumentException.class, () -> binder.bind(null, "progressClock", "TICK_CLOCK"));
        assertThrows(IllegalArgumentException.class, () -> binder.bind("  ", "progressClock", "TICK_CLOCK"));
        assertThrows(IllegalArgumentException.class, () -> binder.readback(null));
        assertThrows(IllegalArgumentException.class, () -> binder.readback(""));
        MechanismRequestIdBinder.Play omitted = new MechanismRequestIdBinder.Play();
        omitted.setOriginalRequestId(null);
        assertNull(omitted.getOriginalRequestId());
        assertThrows(IllegalArgumentException.class, () -> binder.recoverUnknown(omitted));
        assertThrows(IllegalArgumentException.class, () -> binder.recoverOriginal(omitted));
        omitted.setOriginalRequestId("  ");
        assertThrows(IllegalArgumentException.class, () -> binder.recoverUnknown(omitted));
        assertThrows(IllegalArgumentException.class, () -> binder.recoverOriginal(omitted));
        assertNull(binder.existing(null));
        assertNull(binder.existing(""));
        assertNull(binder.existing("  "));
        assertNull(binder.existing(REQUEST));
    }

    @Test
    void recoverUnknownOfNeverSeenIdDoesNotOccupyLedgerThenOneShotApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-never-seen");
        unseen.setOriginalAction(null);
        assertEquals(MechanismRequestIdBinder.UNKNOWN, binder.markUnknown("c03-never-seen").getStatus());
        assertNull(binder.existing("c03-never-seen"));
        Map<String, Object> recovered = binder.recoverUnknown(unseen);
        assertEquals("c03-never-seen", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-never-seen"));

        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        unseen.setPlayerChoice(tick);
        Map<String, Object> applied = clocks.apply(unseen);
        assertEquals("c03-never-seen", applied.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, applied.get("status"));
        assertEquals(Boolean.FALSE, applied.get("replay"));
        assertEquals(Boolean.FALSE, applied.get("mintedNewRequest"));
        assertEquals(1, ((Number) applied.get("value")).intValue());
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-never-seen").getStatus());
        assertEquals("c03-never-seen", binder.existing("c03-never-seen").getRequestId());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenOneShotApplies() throws Exception {
        MechanismRequestIdBinder binder = new MechanismRequestIdBinder();
        ProgressClockConsumer clocks = new ProgressClockConsumer(binder);
        MechanismRequestIdBinder.Play unseen = play(binder);
        unseen.setOriginalRequestId("c03-recover-never-seen");
        unseen.setOriginalAction(null);
        Map<String, Object> recovered = clocks.recoverOriginal(unseen);
        assertEquals("c03-recover-never-seen", recovered.get("requestId"));
        assertEquals(MechanismRequestIdBinder.UNKNOWN, recovered.get("status"));
        assertEquals(Boolean.FALSE, recovered.get("mintedNewRequest"));
        assertEquals(Boolean.FALSE, recovered.get("replay"));
        assertNull(binder.existing("c03-recover-never-seen"));

        ObjectNode tick = choice(MechanismRequestIdBinder.MECH_PROGRESS_CLOCK, ProgressClockConsumer.ACTION_TICK);
        tick.put("var", "counter.alarm");
        unseen.setPlayerChoice(tick);
        Map<String, Object> applied = clocks.apply(unseen);
        assertEquals("c03-recover-never-seen", applied.get("requestId"));
        assertEquals(MechanismRequestIdBinder.APPLIED, applied.get("status"));
        assertEquals(Boolean.FALSE, applied.get("replay"));
        assertEquals(Boolean.FALSE, applied.get("mintedNewRequest"));
        assertEquals(1, ((Number) applied.get("value")).intValue());
        assertEquals(MechanismRequestIdBinder.APPLIED, binder.existing("c03-recover-never-seen").getStatus());
        assertEquals("c03-recover-never-seen", binder.existing("c03-recover-never-seen").getRequestId());
    }
}
