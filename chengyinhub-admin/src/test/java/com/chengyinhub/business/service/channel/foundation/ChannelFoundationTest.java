package com.chengyinhub.business.service.channel.foundation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.foundation.ChannelDefinition.Kind.*;
import static com.chengyinhub.business.service.channel.foundation.ChannelDefinition.Status.*;
import static com.chengyinhub.business.service.channel.foundation.ChannelPermissionOverwrite.Target.*;

class ChannelFoundationTest {
    private ChannelDefinition channel(long id, int guildKind, long guildId, long parent,
            ChannelDefinition.Kind kind, String name, boolean defaultChannel, int position,
            ChannelDefinition.Status status, boolean deleted, Long legacy) {
        return new ChannelDefinition(id, guildKind, guildId, parent, kind, name, null,
                position, defaultChannel, status, 1, 0, true, 0, 0, 0, legacy, deleted);
    }
    private ChannelDefinition text() {
        return channel(12, 1, 7, 0, TEXT, "文字", true, 0, ACTIVE, false, 12L);
    }
    @Test void sharedIdAndTypeAreRequired() {
        text().requireConversation(12, 4);
        channel(12,1,7,0,TEXT,"文字",true,0,ACTIVE,false,null).requireConversation(12,5);
        assertThrows(IllegalArgumentException.class, () -> text().requireConversation(13, 5));
        assertThrows(IllegalArgumentException.class, () -> text().requireConversation(12, 5));
    }
    @Test void migrationCannotReassignConversation() {
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,TEXT,"文字",true,0,ACTIVE,false,13L));
    }
    @Test void retiredVoiceAndStageAreNotAccepted() {
        assertThrows(IllegalArgumentException.class, () -> ChannelDefinition.Kind.fromCode(4));
        assertThrows(IllegalArgumentException.class, () -> ChannelDefinition.Kind.fromCode(5));
        assertThrows(IllegalArgumentException.class, () -> ChannelDefinition.Kind.fromCode(9));
        assertEquals(TOPIC, ChannelDefinition.Kind.fromCode(3));
    }
    @Test void categoryHasGuildButDoesNotCarryMessages() {
        var category = channel(11,1,7,0,CATEGORY,"分类",false,1,ACTIVE,false,null);
        assertTrue(category.lifecycleAllowsHistory());
        assertFalse(category.lifecycleAllowsMessageWrite());
        assertThrows(IllegalArgumentException.class, () -> channel(11,0,0,0,CATEGORY,"分类",false,1,ACTIVE,false,null));
    }
    @Test void allSevenGuildSourcesKeepOriginalNumericCodes() {
        for (int source = 1; source <= 7; source++)
            assertNotNull(channel(12,source,7,0,TEXT,"文字",true,0,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> channel(12,8,7,0,TEXT,"文字",true,0,ACTIVE,false,null));
    }
    @Test void directSystemAndSupportHaveNoGuild() {
        for (var kind : new ChannelDefinition.Kind[] {DIRECT, SYSTEM, SUPPORT}) {
            assertNotNull(channel(12,0,0,0,kind,"sys_12",false,0,ACTIVE,false,null));
            assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,kind,"sys_12",false,0,ACTIVE,false,null));
        }
    }
    @Test void defaultMustBeTextAtZeroPosition() {
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,ANNOUNCEMENT,"公告",true,0,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,TEXT,"文字",true,1,ACTIVE,false,null));
    }
    @Test void parentMustBeSameGuildCategory() {
        var child = channel(12,1,7,11,TEXT,"文字",false,1,ACTIVE,false,null);
        child.requireParent(channel(11,1,7,0,CATEGORY,"分类",false,1,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> child.requireParent(null));
        assertThrows(IllegalArgumentException.class, () -> child.requireParent(channel(11,2,7,0,CATEGORY,"分类",false,1,ACTIVE,false,null)));
        assertThrows(IllegalArgumentException.class, () -> child.requireParent(channel(11,1,8,0,CATEGORY,"分类",false,1,ACTIVE,false,null)));
        assertThrows(IllegalArgumentException.class, () -> child.requireParent(channel(11,1,7,0,TEXT,"文字",false,1,ACTIVE,false,null)));
        assertThrows(IllegalArgumentException.class, () -> child.requireParent(channel(11,1,7,0,CATEGORY,"分类",false,1,ACTIVE,true,null)));
    }
    @Test void selfParentAndNonGuildParentAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,12,TEXT,"文字",false,1,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> channel(12,0,0,11,DIRECT,"1_2",false,1,ACTIVE,false,null));
    }
    @Test void archivedKeepsHistoryButNotWrites() {
        var archived = channel(12,1,7,0,TEXT,"文字",false,1,ARCHIVED,false,null);
        assertTrue(archived.lifecycleAllowsHistory());
        assertFalse(archived.lifecycleAllowsMessageWrite());
    }
    @Test void bannedAndDeletedRestrictHistoryAndWrites() {
        for (var item : new ChannelDefinition[] {
                channel(12,1,7,0,TEXT,"文字",false,1,BANNED,false,null),
                channel(12,1,7,0,TEXT,"文字",false,1,ACTIVE,true,null)}) {
            assertFalse(item.lifecycleAllowsHistory());
            assertFalse(item.lifecycleAllowsMessageWrite());
        }
    }
    @Test void newNamesNormalizeAndCountUnicodeCodePoints() {
        assertEquals("文字", channel(12,1,7,0,TEXT," 文字 ",false,1,ACTIVE,false,null).name());
        assertNotNull(channel(12,1,7,0,TEXT,"😀".repeat(64),false,1,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,TEXT,"😀".repeat(65),false,1,ACTIVE,false,null));
        assertThrows(IllegalArgumentException.class, () -> channel(12,1,7,0,TEXT," ",false,1,ACTIVE,false,null));
    }
    @Test void forbiddenNewNameCharactersDoNotRewriteHistoricalNames() {
        assertThrows(IllegalArgumentException.class, () -> ChannelDefinition.normalizeNewName("bad/name"));
        assertThrows(IllegalArgumentException.class, () -> ChannelDefinition.normalizeNewName("bad#name"));
        assertEquals(" old/name ", channel(12,1,7,0,TEXT," old/name ",true,0,ACTIVE,false,12L).name());
    }
    @Test void allLegacyConversationTypesRemainUnchanged() {
        var kinds = new ChannelDefinition.Kind[] {DIRECT, SYSTEM, SUPPORT};
        for (int i = 0; i < kinds.length; i++) {
            var item = channel(12,0,0,0,kinds[i],"legacy_12",false,0,ACTIVE,false,12L);
            item.requireConversation(12, i + 1);
            assertThrows(IllegalArgumentException.class, () -> item.requireConversation(12, 5));
        }
    }
    @Test void visibilityDoesNotGrantAuthorization() {
        var privateChannel = new ChannelDefinition(12,1,7,0,TEXT,"文字",null,0,true,ACTIVE,2,0,true,0,0,0,null,false);
        assertEquals(text().lifecycleAllowsHistory(), privateChannel.lifecycleAllowsHistory());
        assertEquals(text().lifecycleAllowsMessageWrite(), privateChannel.lifecycleAllowsMessageWrite());
    }
    @Test void everyManagementBitIsDeniedAsAllowButValidAsDeny() {
        for (int bit = 0; bit < 25; bit++) {
            long mask = 1L << bit;
            if ((mask & ChannelPermissionOverwrite.GUILD_MANAGEMENT_BITS) != 0) {
                for (var target : ChannelPermissionOverwrite.Target.values()) {
                    long id = target == EVERYONE ? 0 : 2;
                    assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(target,id,mask,0));
                    assertNotNull(new ChannelPermissionOverwrite(target,id,0,mask));
                }
            }
        }
    }
    @Test void malformedMasksAndOverlappingBitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(ROLE,1,-1,0));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(ROLE,1,1L<<25,0));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(ROLE,1,1,1));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(ROLE,1,0,-1));
    }
    @Test void targetIdentityIsStrict() {
        assertNotNull(new ChannelPermissionOverwrite(EVERYONE,0,1,0));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(EVERYONE,1,0,0));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(MEMBER,0,0,0));
        assertThrows(IllegalArgumentException.class, () -> new ChannelPermissionOverwrite(null,0,0,0));
    }
    @Test void wirePermissionUsesHexString() {
        assertEquals("0x1FFFFFF", ChannelPermissionOverwrite.hex(0x1FFFFFFL));
        assertEquals("0x0", ChannelPermissionOverwrite.hex(0));
        assertThrows(IllegalArgumentException.class, () -> ChannelPermissionOverwrite.hex(-1));
    }
    @Test void pendingAndFinalDeletionDenyContentEvenBeforeDelFlagChanges() {
        for (var status : new ChannelDefinition.Status[] {PENDING_DELETE, DELETED}) {
            var row = channel(12,1,7,0,TEXT,"hidden",false,1,status,false,null);
            assertFalse(row.lifecycleAllowsHistory());
            assertFalse(row.lifecycleAllowsMessageWrite());
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireContentRead(row));
            assertThrows(SecurityException.class, () -> ChannelLifecycleAdmission.requireMessageWrite(row));
        }
        assertEquals(0, ACTIVE.code()); assertEquals(1, ARCHIVED.code()); assertEquals(2, BANNED.code());
        assertEquals(3, PENDING_DELETE.code()); assertEquals(4, DELETED.code());
    }
    @Test void inaccessibleAncestorCannotPassLiveParentValidation() {
        var child=channel(12,1,7,11,TEXT,"child",false,1,ACTIVE,false,null);
        for (var status : new ChannelDefinition.Status[] {BANNED,PENDING_DELETE,DELETED}) {
            var parent=channel(11,1,7,0,CATEGORY,"hidden",false,1,status,false,null);
            assertThrows(IllegalArgumentException.class,()->child.requireParent(parent));
        }
    }
}
