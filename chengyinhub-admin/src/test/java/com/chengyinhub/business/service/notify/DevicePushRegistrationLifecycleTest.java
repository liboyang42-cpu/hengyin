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
}
