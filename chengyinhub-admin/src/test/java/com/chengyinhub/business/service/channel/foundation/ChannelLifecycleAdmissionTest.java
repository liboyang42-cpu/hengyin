package com.chengyinhub.business.service.channel.foundation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChannelLifecycleAdmissionTest {
    private ChannelDefinition channel(ChannelDefinition.Kind kind,
            ChannelDefinition.Status status, boolean deleted, int visibility) {
        return new ChannelDefinition(123, kind.hasGuild() ? 1 : 0,
                kind.hasGuild() ? 7 : 0, 0, kind, "private name", null, 1,
                false, status, visibility, 0, true, 0, 0, 0, null, deleted);
    }

    @Test void missingMetadataFailsClosedForReadsAndWrites() {
        assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireContentRead(null));
        assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(null));
    }

    @Test void activeTextPassesOnlyTheLifecycleRestriction() {
        var value = channel(ChannelDefinition.Kind.TEXT, ChannelDefinition.Status.ACTIVE, false, 1);
        assertDoesNotThrow(() -> ChannelLifecycleAdmission.requireContentRead(value));
        assertDoesNotThrow(() -> ChannelLifecycleAdmission.requireMessageWrite(value));
    }

    @Test void archivedHistoryRemainsReadableButMessagesAreReadOnly() {
        for (var kind : ChannelDefinition.Kind.values()) {
            var value = channel(kind, ChannelDefinition.Status.ARCHIVED, false, 1);
            assertDoesNotThrow(() -> ChannelLifecycleAdmission.requireContentRead(value));
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(value));
        }
    }

    @Test void bannedChannelsNeverExposeContentRegardlessOfKind() {
        for (var kind : ChannelDefinition.Kind.values()) {
            var value = channel(kind, ChannelDefinition.Status.BANNED, false, 1);
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireContentRead(value));
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(value));
        }
    }

    @Test void deletionDominatesEveryLifecycleStatus() {
        for (var status : ChannelDefinition.Status.values()) {
            var value = channel(ChannelDefinition.Kind.TEXT, status, true, 1);
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireContentRead(value));
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(value));
        }
    }

    @Test void categoryCanBeReadButCannotReceiveMessages() {
        var value = channel(ChannelDefinition.Kind.CATEGORY, ChannelDefinition.Status.ACTIVE, false, 1);
        assertDoesNotThrow(() -> ChannelLifecycleAdmission.requireContentRead(value));
        assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(value));
    }

    @Test void visibilityProjectionNeverOverridesLifecycle() {
        for (int visibility : new int[] {1, 2}) {
            var active = channel(ChannelDefinition.Kind.TEXT, ChannelDefinition.Status.ACTIVE, false, visibility);
            var banned = channel(ChannelDefinition.Kind.TEXT, ChannelDefinition.Status.BANNED, false, visibility);
            assertDoesNotThrow(() -> ChannelLifecycleAdmission.requireContentRead(active));
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireContentRead(banned));
        }
    }

    @Test void missingDeletedAndBannedFailuresDoNotDiscloseChannelDetails() {
        String absent = assertThrows(SecurityException.class,
                () -> ChannelLifecycleAdmission.requireContentRead(null)).getMessage();
        for (var value : new ChannelDefinition[] {
                channel(ChannelDefinition.Kind.TEXT, ChannelDefinition.Status.ACTIVE, true, 1),
                channel(ChannelDefinition.Kind.TEXT, ChannelDefinition.Status.BANNED, false, 1)}) {
            var read = assertThrows(SecurityException.class,
                    () -> ChannelLifecycleAdmission.requireContentRead(value));
            var write = assertThrows(SecurityException.class,
                    () -> ChannelLifecycleAdmission.requireMessageWrite(value));
            assertEquals(absent, read.getMessage());
            assertEquals(absent, write.getMessage());
            assertFalse(read.getMessage().contains(value.name()));
            assertNull(read.getCause());
        }
    }
}
