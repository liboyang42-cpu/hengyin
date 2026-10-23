package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C12 isolation: missing requestId stays null (never stored as {@code ""});
 * device switch/revoke does not rewrite the other device; UNKNOWN recover
 * is not APPLIED+CURRENT.
 */
class C09AccountSwitchRestartIsolationTest {

    @Test
    void missingRequestIdDoesNotInsertEmptyReceipt() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        assertThrows(IllegalArgumentException.class, () -> producer.accept(
                new ImmutableReceiptProducer.OriginalRequest(null, ImmutableReceiptProducer.Kind.PIN,
                        "{\"op\":\"pin\"}", ImmutableReceiptProducer.OUTCOME_UNKNOWN)));
        assertThrows(IllegalArgumentException.class, () -> producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("", ImmutableReceiptProducer.Kind.PIN,
                        "{\"op\":\"pin\"}", ImmutableReceiptProducer.OUTCOME_UNKNOWN)));
        assertEquals(0, store.size());
        assertNull(store.find(""));
        assertNull(store.find(null));
        assertThrows(IllegalArgumentException.class, () -> producer.recover(null, null));
        assertThrows(IllegalArgumentException.class, () -> producer.offlineReopen("  ", null));
        ImmutableReceiptProducer.OriginalReceipt missing = producer.unknownRecover("  ", null);
        assertNull(missing.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, missing.outcome);
        assertEquals(ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED, missing.recoveryNote);
        assertNull(missing.asRequeryableView().get("requestId"));
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt neverSeen = producer.unknownRecover("evt-never-c09", null);
        assertEquals("evt-never-c09", neverSeen.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, neverSeen.outcome);
        assertNull(store.find("evt-never-c09"));
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("evt-never-c09", ImmutableReceiptProducer.Kind.PIN,
                        "{\"op\":\"pin\"}", ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals(1, store.size());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyLedgerThenAcceptApplies() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.OriginalReceipt blank = producer.recoverOriginal(null, null);
        assertNull(blank.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, blank.outcome);
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt recovered = producer.recoverOriginal("evt-recover-never-c09", null);
        assertEquals("evt-recover-never-c09", recovered.requestId);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, recovered.outcome);
        assertNull(store.find("evt-recover-never-c09"));
        assertEquals(0, store.size());
        ImmutableReceiptProducer.OriginalReceipt first = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("evt-recover-never-c09",
                        ImmutableReceiptProducer.Kind.PIN, "{\"op\":\"pin\"}",
                        ImmutableReceiptProducer.OUTCOME_ACCEPTED));
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED, first.outcome);
        assertEquals("evt-recover-never-c09", first.requestId);
        assertEquals(1, store.size());
        assertEquals(ImmutableReceiptProducer.OUTCOME_ACCEPTED,
                producer.recoverOriginal("evt-recover-never-c09", null).outcome);
    }

    @Test
    void revokedDeviceDoesNotReauthorizeOtherDeviceOrAppliedProjection() {
        InMemoryNotifyFixtures.MemoryDeviceStore store = new InMemoryNotifyFixtures.MemoryDeviceStore();
        DevicePushRegistrationLifecycle lifecycle = new DevicePushRegistrationLifecycle(store);
        DevicePushRegistrationLifecycle.Registration first = lifecycle.register(
                "device-A", DevicePushRegistrationLifecycle.ENV_SANDBOX, true, "fixture-token-a");
        DevicePushRegistrationLifecycle.Registration other = lifecycle.register(
                "device-B", DevicePushRegistrationLifecycle.ENV_SANDBOX, true, "fixture-token-b");
        assertEquals(2, store.size());
        lifecycle.revoke("device-A");
        DevicePushRegistrationLifecycle.SendDecision a = lifecycle.maySend(
                "device-A", ImmutableReceiptProducer.PROJECTION_APPLIED);
        DevicePushRegistrationLifecycle.SendDecision b = lifecycle.maySend("device-B", null);
        assertFalse(a.allowed);
        assertTrue(b.allowed);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_REVOKED, a.registration.status);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE, b.registration.status);
        assertNotEquals(first.deviceId, other.deviceId);
        assertFalse(lifecycle.treatsAppliedProjectionAsAuthorization(ImmutableReceiptProducer.PROJECTION_APPLIED));
    }

    @Test
    void restartRecoverKeepsUnknownAndDoesNotTreatAppliedAsOriginal() {
        InMemoryNotifyFixtures.MemoryReceiptStore store = new InMemoryNotifyFixtures.MemoryReceiptStore();
        ImmutableReceiptProducer producer = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.OriginalReceipt original = producer.accept(
                new ImmutableReceiptProducer.OriginalRequest("evt-c09-restart", ImmutableReceiptProducer.Kind.EDIT,
                        "{\"op\":\"edit\"}", ImmutableReceiptProducer.OUTCOME_UNKNOWN));
        ImmutableReceiptProducer restarted = new ImmutableReceiptProducer(store);
        ImmutableReceiptProducer.AppliedProjection applied = new ImmutableReceiptProducer.AppliedProjection(
                "evt-c09-restart", ImmutableReceiptProducer.PROJECTION_APPLIED);
        ImmutableReceiptProducer.OriginalReceipt recovered = restarted.offlineReopen("evt-c09-restart", applied);
        assertEquals(original.payloadHash, recovered.payloadHash);
        assertEquals(ImmutableReceiptProducer.OUTCOME_UNKNOWN, recovered.outcome);
        assertNotEquals(ImmutableReceiptProducer.PROJECTION_APPLIED, recovered.outcome);
        assertEquals(ImmutableReceiptProducer.UNKNOWN_NOT_RECOVERED,
                restarted.unknownRecover("evt-c09-restart", applied).recoveryNote);
        assertEquals("evt-c09-restart", recovered.asRequeryableView().get("requestId"));
        assertFalse(restarted.treatsAppliedProjectionAsOriginalProof(applied));
    }
}
