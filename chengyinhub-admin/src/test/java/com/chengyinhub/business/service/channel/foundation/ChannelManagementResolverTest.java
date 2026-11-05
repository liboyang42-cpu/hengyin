package com.chengyinhub.business.service.channel.foundation;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.channel.foundation.ChannelManagementResolver.*;
import static com.chengyinhub.business.service.channel.foundation.ChannelPermissionOverwrite.Target.*;

class ChannelManagementResolverTest {
    private static final Instant NOW=Instant.parse("2026-11-05T00:00:00Z");
    private ChannelDefinition channel(long id,long parent,ChannelDefinition.Kind kind,ChannelDefinition.Status status) {
        return new ChannelDefinition(id,1,7,parent,kind,"channel_"+id,null,1,false,status,2,0,true,0,0,0,null,false);
    }
    private Layer target(Overwrite... rows) {
        return new Layer(channel(12,0,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ACTIVE),List.of(rows));
    }
    private Snapshot snapshot(long everyone,List<Role> roles,List<Grant> grants,Layer... layers) {
        return new Snapshot(Encoding.FORMAL_25_V1,99,1,7,true,true,true,false,NOW,
                new Role(1,1,false,everyone),roles,grants,List.of(layers));
    }
    private Grant grant(long role,Scope scope,long id) { return new Grant(role,scope,id,true,null); }
    @Test void ordinaryMemberCannotEnterPrivateWithoutGrant() {
        var r=resolve(snapshot(0,List.of(),List.of(),target(new Overwrite(MEMBER,99,0,0))));
        assertEquals(0,r.contentPermissions()); assertFalse(r.mayArchive());
    }
    @Test void privateRoleOrPersonalAuthorizationWorksWithoutSeparateMembershipRow() {
        var roles=List.of(new Role(2,1,false,0));
        assertEquals(VIEW,resolve(snapshot(0,roles,List.of(grant(2,Scope.GUILD,7)),
                target(new Overwrite(ROLE,2,VIEW,0)))).contentPermissions());
        assertEquals(VIEW,resolve(snapshot(0,List.of(),List.of(),target(new Overwrite(MEMBER,99,VIEW,0)))).contentPermissions());
    }
    @Test void categoryMemberDenyIsInheritedAndChildCanOverride() {
        var parent=new Layer(channel(10,0,ChannelDefinition.Kind.CATEGORY,ChannelDefinition.Status.ACTIVE),
                List.of(new Overwrite(MEMBER,99,0,VIEW)));
        var child=new Layer(channel(12,10,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ACTIVE),List.of());
        assertEquals(0,resolve(snapshot(VIEW,List.of(),List.of(),parent,child)).contentPermissions());
        var exception=new Layer(child.channel(),List.of(new Overwrite(MEMBER,99,VIEW,0)));
        assertEquals(VIEW,resolve(snapshot(VIEW,List.of(),List.of(),parent,exception)).contentPermissions());
    }
    @Test void matchingMemberOverrideWinsAfterRoleAndEveryone() {
        var r=resolve(snapshot(VIEW,List.of(new Role(2,1,false,0)),List.of(grant(2,Scope.GUILD,7)),
                target(new Overwrite(EVERYONE,0,0,VIEW),new Overwrite(ROLE,2,VIEW,0),new Overwrite(MEMBER,99,0,VIEW))));
        assertEquals(0,r.contentPermissions());
    }
    @Test void multipleRolesAggregateBeforeApplyingTheirOverwrite() {
        var r=resolve(snapshot(0,List.of(new Role(2,1,false,0),new Role(3,1,false,0)),
                List.of(grant(2,Scope.GUILD,7),grant(3,Scope.GUILD,7)),
                target(new Overwrite(ROLE,2,VIEW,0),new Overwrite(ROLE,3,0,VIEW))));
        assertEquals(VIEW,r.contentPermissions());
    }
    @Test void channelManagerCanArchiveOnlyExactTarget() {
        var r=resolve(snapshot(VIEW,List.of(new Role(2,1,false,MANAGE_CHANNEL|(1L<<13))),
                List.of(grant(2,Scope.CHANNEL,12)),target()));
        assertTrue(r.mayArchive()); assertEquals(VIEW,r.contentPermissions());
        r.requireArchive(99,1,7,12);
        assertThrows(SecurityException.class,()->r.requireArchive(99,1,7,13));
        assertThrows(SecurityException.class,()->r.requireArchive(100,1,7,12));
        assertThrows(SecurityException.class,()->r.requireArchive(99,2,7,12));
    }
    @Test void categoryScopedManagerDoesNotBecomeChildManager() {
        var parent=new Layer(channel(10,0,ChannelDefinition.Kind.CATEGORY,ChannelDefinition.Status.ACTIVE),List.of());
        var child=new Layer(channel(12,10,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ACTIVE),List.of());
        assertFalse(resolve(snapshot(VIEW,List.of(new Role(2,1,false,MANAGE_CHANNEL)),
                List.of(grant(2,Scope.CHANNEL,10)),parent,child)).mayArchive());
    }
    @Test void revokedExpiredAndDisabledRolesNeverGrantManagement() {
        var roles=List.of(new Role(2,1,false,MANAGE_CHANNEL),new Role(3,0,false,MANAGE_CHANNEL),new Role(4,1,true,MANAGE_CHANNEL));
        var grants=List.of(new Grant(2,Scope.GUILD,7,false,null),new Grant(2,Scope.GUILD,7,true,NOW),
                grant(3,Scope.GUILD,7),grant(4,Scope.GUILD,7));
        assertFalse(resolve(snapshot(VIEW,roles,grants,target())).mayArchive());
    }
    @Test void channelCannotGrantManagementButCanDenyIt() {
        assertThrows(IllegalArgumentException.class,()->new Overwrite(MEMBER,99,MANAGE_CHANNEL,0));
        assertThrows(IllegalArgumentException.class,()->new Overwrite(ROLE,2,1L<<16,0));
        assertFalse(resolve(snapshot(VIEW|MANAGE_CHANNEL,List.of(),List.of(),
                target(new Overwrite(MEMBER,99,0,MANAGE_CHANNEL)))).mayArchive());
    }
    @Test void archiveBlocksMessageWritesButAllowsUnarchiveAuthority() {
        var archived=new Layer(channel(12,0,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ARCHIVED),List.of());
        var r=resolve(snapshot(VIEW|MANAGE_CHANNEL|(1L<<1),List.of(),List.of(),archived));
        assertTrue(r.mayArchive()); assertEquals(VIEW,r.contentPermissions());
    }
    @Test void bannedDeletedMissingAncestorAndForeignScopeFailClosed() {
        for(var status:List.of(ChannelDefinition.Status.BANNED,ChannelDefinition.Status.PENDING_DELETE,ChannelDefinition.Status.DELETED))
            assertThrows(SecurityException.class,()->resolve(snapshot(ALL,List.of(),List.of(),
                    new Layer(channel(12,0,ChannelDefinition.Kind.TEXT,status),List.of()))));
        assertThrows(SecurityException.class,()->resolve(snapshot(ALL,List.of(),List.of(),
                new Layer(channel(12,10,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ACTIVE),List.of()))));
    }
    @Test void unknownAndLegacyEncodingAreNeverSilentlyConverted() {
        for(var encoding:List.of(Encoding.UNKNOWN,Encoding.LEGACY))
            assertThrows(IllegalArgumentException.class,()->resolve(new Snapshot(encoding,99,1,7,true,true,true,false,NOW,
                    new Role(1,1,false,ALL),List.of(),List.of(),List.of(target()))));
    }
    @Test void currentSafetyAndMembershipOverrideAllPermissions() {
        assertThrows(SecurityException.class,()->resolve(new Snapshot(Encoding.FORMAL_25_V1,99,1,7,true,true,true,true,NOW,
                new Role(1,1,false,ALL),List.of(),List.of(),List.of(target()))));
        assertThrows(SecurityException.class,()->resolve(new Snapshot(Encoding.FORMAL_25_V1,99,1,7,true,true,false,false,NOW,
                new Role(1,1,false,ALL),List.of(),List.of(),List.of(target()))));
    }
    @Test void duplicateTargetsAndMalformedRoleMasksAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new Role(2,1,false,1L<<25));
        assertThrows(IllegalArgumentException.class,()->resolve(snapshot(VIEW,List.of(),List.of(),
                target(new Overwrite(MEMBER,99,0,0),new Overwrite(MEMBER,99,0,0)))));
    }
    @Test void authorityAdapterCannotRunWithoutActualTransaction() {
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        var authority=new ChannelSnapshotManagementAuthority((actor,kind,guild,id)-> {
            called.set(true); return snapshot(ALL,List.of(),List.of(),target());
        });
        assertThrows(IllegalStateException.class,()->authority.requireCurrentManagement(99,1,7,12));
        assertFalse(called.get());
    }
    @Test void scopedManagementCannotResurrectAnAncestorManagementDeny() {
        var parent=new Layer(channel(10,0,ChannelDefinition.Kind.CATEGORY,ChannelDefinition.Status.ACTIVE),
                List.of(new Overwrite(MEMBER,99,0,MANAGE_CHANNEL)));
        var child=new Layer(channel(12,10,ChannelDefinition.Kind.TEXT,ChannelDefinition.Status.ACTIVE),List.of());
        assertFalse(resolve(snapshot(VIEW,List.of(new Role(2,1,false,MANAGE_CHANNEL)),
                List.of(grant(2,Scope.CHANNEL,12)),parent,child)).mayArchive());
    }
}
