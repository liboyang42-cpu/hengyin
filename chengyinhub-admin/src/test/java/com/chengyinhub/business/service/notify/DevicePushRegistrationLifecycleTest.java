package com.chengyinhub.business.service.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DevicePushRegistrationLifecycleTest {

    @Test
    void registerSilentThenRevokeStopsFurtherSend() {
        InMemoryNotifyFixtures.MemoryDeviceStore store = new InMemoryNotifyFixtures.MemoryDeviceStore();
        DevicePushRegistrationLifecycle lifecycle = new DevicePushRegistrationLifecycle(store);
        DevicePushRegistrationLifecycle.Registration registered = lifecycle.register(
                "dev-c09", DevicePushRegistrationLifecycle.ENV_SANDBOX, true, "fixture-token-c09");
        assertTrue(registered.silent);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE, registered.status);
        assertTrue(lifecycle.maySend("dev-c09", ImmutableReceiptProducer.PROJECTION_APPLIED).allowed);

        lifecycle.disable("dev-c09");
        assertFalse(lifecycle.maySend("dev-c09", ImmutableReceiptProducer.PROJECTION_APPLIED).allowed);

        lifecycle.revoke("dev-c09");
        DevicePushRegistrationLifecycle.SendDecision afterRevoke =
                lifecycle.maySend("dev-c09", ImmutableReceiptProducer.PROJECTION_APPLIED);
        assertFalse(afterRevoke.allowed);
        assertEquals("REVOKED_OR_DISABLED", afterRevoke.reason);
        assertTrue(afterRevoke.currentProjectionClaimsApplied);
        assertFalse(lifecycle.treatsAppliedProjectionAsAuthorization(ImmutableReceiptProducer.PROJECTION_APPLIED));
        assertThrows(IllegalStateException.class, () -> lifecycle.register(
                "dev-c09", DevicePushRegistrationLifecycle.ENV_SANDBOX, true, "fixture-token-c09"));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.register(
                "dev-new", DevicePushRegistrationLifecycle.ENV_SANDBOX, false, "prod-apns-key"));
        DevicePushRegistrationLifecycle.Registration original = lifecycle.recoverOriginal(
                "dev-c09", ImmutableReceiptProducer.PROJECTION_APPLIED);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_REVOKED, original.status);
        int occupied = store.size();
        assertEquals("UNKNOWN", lifecycle.recoverUnknown(
                "dev-missing", ImmutableReceiptProducer.PROJECTION_APPLIED).status);
        assertNull(store.find("dev-missing"));
        assertNull(lifecycle.recoverUnknown("  ", ImmutableReceiptProducer.PROJECTION_APPLIED).deviceId);
        assertEquals("UNKNOWN", lifecycle.recoverUnknown("  ", ImmutableReceiptProducer.PROJECTION_APPLIED).status);
        assertEquals(occupied, store.size());
        assertFalse(lifecycle.treatsAppliedProjectionAsOriginalProof(ImmutableReceiptProducer.PROJECTION_APPLIED));
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueRegisterApplies() {
        InMemoryNotifyFixtures.MemoryDeviceStore store = new InMemoryNotifyFixtures.MemoryDeviceStore();
        DevicePushRegistrationLifecycle lifecycle = new DevicePushRegistrationLifecycle(store);
        DevicePushRegistrationLifecycle.Registration blank = lifecycle.recoverOriginal("  ", null);
        assertNull(blank.deviceId);
        assertEquals("UNKNOWN", blank.status);
        assertEquals(0, store.size());
        DevicePushRegistrationLifecycle.Registration recovered = lifecycle.recoverOriginal(
                "dev-recover-never-c09", null);
        assertEquals("dev-recover-never-c09", recovered.deviceId);
        assertEquals("UNKNOWN", recovered.status);
        assertNull(store.find("dev-recover-never-c09"));
        assertEquals(0, store.size());
        DevicePushRegistrationLifecycle.Registration first = lifecycle.register(
                "dev-recover-never-c09", DevicePushRegistrationLifecycle.ENV_SANDBOX, true,
                "fixture-token-c09");
        assertEquals("dev-recover-never-c09", first.deviceId);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE, first.status);
        assertEquals(1, store.size());
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE,
                lifecycle.recoverOriginal("dev-recover-never-c09", null).status);
        assertEquals(1, store.size());
    }

    @Test
    void unusedRevokeOfNeverSeenDoesNotOccupyThenUniqueRegisterApplies() {
        InMemoryNotifyFixtures.MemoryDeviceStore store = new InMemoryNotifyFixtures.MemoryDeviceStore();
        DevicePushRegistrationLifecycle lifecycle = new DevicePushRegistrationLifecycle(store);
        assertThrows(IllegalStateException.class, () -> lifecycle.revoke("dev-revoke-never-c09"));
        assertEquals(0, store.size(), "未见设备 revoke 观察不得占账");
        assertNull(store.find("dev-revoke-never-c09"));
        DevicePushRegistrationLifecycle.Registration unused = lifecycle.recoverOriginal(
                "dev-revoke-never-c09", null);
        assertEquals("dev-revoke-never-c09", unused.deviceId);
        assertEquals("UNKNOWN", unused.status);
        DevicePushRegistrationLifecycle.Registration first = lifecycle.register(
                "dev-revoke-never-c09", DevicePushRegistrationLifecycle.ENV_SANDBOX, true,
                "fixture-token-c09");
        assertEquals("dev-revoke-never-c09", first.deviceId);
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE, first.status);
        assertEquals(1, store.size());
        assertEquals(DevicePushRegistrationLifecycle.STATUS_ACTIVE,
                lifecycle.recoverOriginal("dev-revoke-never-c09", null).status);
        assertEquals(1, store.size());
    }
}
