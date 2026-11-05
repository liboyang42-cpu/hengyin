package com.chengyinhub.business.service.channel.foundation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic resolution of a server-loaded snapshot, NOT a login or database adapter.
 * Only the formal 25-bit encoding is accepted. Historical unversioned masks must not be
 * tagged FORMAL_25_V1 without verified provenance. No legacy conversion is inferred.
 * The result authorizes only archive administration for its exact actor/channel scope;
 * it is not authority to delete, restore, grant roles or modify permissions.
 */
public final class ChannelManagementResolver {
    public enum Encoding { FORMAL_25_V1, LEGACY, UNKNOWN }
    public enum Scope { GUILD, CHANNEL }
    public static final long ALL = 0x1FFFFFFL;
    public static final long VIEW = 1L;
    public static final long MANAGE_CHANNEL = 1L << 12;
    // Preserve the existing formal DDL restriction, and never elevate ANY named
    // management capability through a channel override while its legacy mask is disputed.
    private static final long MANAGEMENT = (0x7FFL << 12) | (1L << 24);
    private static final long FORBIDDEN_ALLOW = 0x6BC00L | MANAGEMENT;
    private static final long READ_ONLY = VIEW | (1L<<6) | (1L<<23);

    public record Role(long id, int status, boolean deleted, long permissions) {
        public Role { if (id<=0 || (status!=0 && status!=1)) invalid(); bits(permissions); }
    }
    public record Grant(long roleId, Scope scope, long scopeId, boolean active, Instant expiresAt) {
        public Grant { if (roleId<=0 || scope==null || scopeId<=0) invalid(); }
    }
    public record Overwrite(ChannelPermissionOverwrite.Target target, long targetId, long allow, long deny) {
        public Overwrite {
            Objects.requireNonNull(target); bits(allow); bits(deny);
            if ((target==ChannelPermissionOverwrite.Target.EVERYONE ? targetId!=0 : targetId<=0)
                    || (allow & deny)!=0 || (allow & FORBIDDEN_ALLOW)!=0) invalid();
        }
    }
    public record Layer(ChannelDefinition channel, List<Overwrite> overwrites) {
        public Layer { Objects.requireNonNull(channel); overwrites=List.copyOf(overwrites); }
    }
    /** Layers must contain the full root-to-target path; absence is never permission. */
    public record Snapshot(Encoding encoding, long actor, int guildKind, long guildId,
            boolean principalActive, boolean guildActive, boolean memberActive, boolean safetyBlocked,
            Instant observedAt, Role everyone, List<Role> roles, List<Grant> grants, List<Layer> layers) {
        public Snapshot {
            Objects.requireNonNull(encoding); Objects.requireNonNull(observedAt); Objects.requireNonNull(everyone);
            roles=List.copyOf(roles); grants=List.copyOf(grants); layers=List.copyOf(layers);
            if (actor<=0 || guildKind<1 || guildKind>7 || guildId<=0 || layers.isEmpty() || layers.size()>33) invalid();
        }
    }
    public record Resolution(long actor, int guildKind, long guildId, long channelId,
            long contentPermissions, boolean mayArchive) {
        public void requireArchive(long expectedActor, int expectedKind, long expectedGuild, long expectedChannel) {
            if (!mayArchive || actor!=expectedActor || guildKind!=expectedKind
                    || guildId!=expectedGuild || channelId!=expectedChannel) deny();
        }
    }
    private ChannelManagementResolver() { }

    public static Resolution resolve(Snapshot s) {
        Objects.requireNonNull(s);
        if (s.encoding()!=Encoding.FORMAL_25_V1) throw new IllegalArgumentException("Unverified permission encoding");
        if (!s.principalActive() || !s.guildActive() || !s.memberActive() || s.safetyBlocked()) deny();
        if (s.everyone().status()!=1 || s.everyone().deleted()) deny();
        Set<Long> channels=new HashSet<>();
        long parent=0;
        boolean archived=false;
        for (int i=0;i<s.layers().size();i++) {
            ChannelDefinition ch=s.layers().get(i).channel();
            if (!channels.add(ch.id()) || ch.guildKind()!=s.guildKind() || ch.guildId()!=s.guildId()
                    || ch.parentId()!=parent || !ch.lifecycleAllowsHistory()
                    || (i<s.layers().size()-1 && ch.kind()!=ChannelDefinition.Kind.CATEGORY)) deny();
            archived |= ch.status()==ChannelDefinition.Status.ARCHIVED;
            parent=ch.id();
        }
        long target=parent;
        java.util.Map<Long,Role> roles=new java.util.HashMap<>();
        for (Role role:s.roles()) if (role.id()==s.everyone().id() || roles.put(role.id(),role)!=null) invalid();
        Set<Long> guildRoles=new HashSet<>();
        java.util.Map<Long,Set<Long>> scopedRoles=new java.util.HashMap<>();
        long base=s.everyone().permissions();
        for (Grant grant:s.grants()) {
            if (!grant.active() || (grant.expiresAt()!=null && !grant.expiresAt().isAfter(s.observedAt()))) continue;
            Role role=roles.get(grant.roleId());
            if (role==null) invalid();
            if (role.status()!=1 || role.deleted()) continue;
            if (grant.scope()==Scope.GUILD) {
                if (grant.scopeId()!=s.guildId()) invalid();
                guildRoles.add(role.id()); base |= role.permissions();
            } else if (channels.contains(grant.scopeId())) {
                scopedRoles.computeIfAbsent(grant.scopeId(), ignored -> new HashSet<>()).add(role.id());
            }
        }
        // Only the target's scoped roles participate, including inherited restrictions.
        // Inject once before inheritance so a later stage cannot re-add denied management.
        Set<Long> targetRoles=scopedRoles.getOrDefault(target,Set.of());
        for (long roleId:targetRoles)
            base |= roles.get(roleId).permissions() & (~MANAGEMENT | MANAGE_CHANNEL);
        long effective=base;
        for (Layer layer:s.layers()) {
            Set<Long> applicable=new HashSet<>(guildRoles);
            applicable.addAll(targetRoles);
            long everyoneAllow=0,everyoneDeny=0,roleAllow=0,roleDeny=0,memberAllow=0,memberDeny=0;
            Set<String> targets=new HashSet<>();
            for (Overwrite o:layer.overwrites()) {
                if (!targets.add(o.target()+":"+o.targetId())) invalid();
                switch(o.target()) {
                    case EVERYONE -> { everyoneAllow=o.allow(); everyoneDeny=o.deny(); }
                    case ROLE -> { if(applicable.contains(o.targetId())) { roleAllow|=o.allow(); roleDeny|=o.deny(); } }
                    case MEMBER -> { if(o.targetId()==s.actor()) { memberAllow=o.allow(); memberDeny=o.deny(); } }
                    case BOT -> { /* Human-member resolution never receives bot grants. */ }
                }
            }
            effective=apply(effective,everyoneAllow,everyoneDeny);
            effective=apply(effective,roleAllow,roleDeny);
            effective=apply(effective,memberAllow,memberDeny);
        }
        boolean visible=(effective & VIEW)!=0;
        boolean archive=visible && (effective & MANAGE_CHANNEL)!=0;
        long content=visible ? effective & ~MANAGEMENT : 0;
        if (archived) content &= READ_ONLY;
        if ((content & (1L<<1))==0) content &= ~((1L<<2)|(1L<<4)|(1L<<7));
        // visibility/private flags and member-settings row existence never create grants.
        return new Resolution(s.actor(),s.guildKind(),s.guildId(),target,content,archive);
    }
    private static long apply(long base,long allow,long deny) { return (base & ~deny) | allow; }
    private static void bits(long value) { if(value<0 || (value & ~ALL)!=0) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("Invalid permission snapshot"); }
    private static void deny() { throw new SecurityException("Channel unavailable"); }
}
