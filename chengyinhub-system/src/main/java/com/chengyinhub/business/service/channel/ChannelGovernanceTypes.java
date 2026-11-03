package com.chengyinhub.business.service.channel;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * C08 独占类型。P38 ACCOUNT4 与 P04 双版本 CAS 是既有合同事实，不是缺失 API。
 * 不改公共 IM journal / send / receipt。WAITING_SOURCE：D5 native 43/45/46/47。
 */
public final class ChannelGovernanceTypes {

    public static final String ACCOUNT4 = "ACCOUNT4";
    public static final String V12_DOCX = "v12.docx";
    public static final String PACK_232 = "232.pack";
    public static final String UNPUSHED_BACKEND = "e16c2c5";

    public static boolean v12DocxPresent() {
        return false;
    }

    public static boolean pack232Present() {
        return false;
    }

    public static boolean unpushedBackendPresent() {
        return false;
    }

    private ChannelGovernanceTypes() {
    }

    public enum ConversationKind {
        GROUP, DIRECT, SYSTEM, MERCHANT
    }

    public enum ChannelRole {
        NONE,
        APPLICANT,
        MEMBER,
        MODERATOR,
        OWNER;

        public Set<ChannelCapability> capabilities() {
            switch (this) {
                case APPLICANT:
                    return EnumSet.of(ChannelCapability.APPLY);
                case MEMBER:
                    return EnumSet.of(ChannelCapability.READ, ChannelCapability.WRITE, ChannelCapability.LEAVE);
                case MODERATOR:
                    return EnumSet.of(ChannelCapability.READ, ChannelCapability.WRITE, ChannelCapability.LEAVE,
                            ChannelCapability.INVITE, ChannelCapability.BAN, ChannelCapability.GOVERN);
                case OWNER:
                    return EnumSet.of(ChannelCapability.READ, ChannelCapability.WRITE, ChannelCapability.LEAVE,
                            ChannelCapability.INVITE, ChannelCapability.BAN, ChannelCapability.GOVERN,
                            ChannelCapability.APPEAL);
                case NONE:
                default:
                    return Collections.emptySet();
            }
        }
    }

    public enum ChannelCapability {
        READ, WRITE, GOVERN, INVITE, APPLY, LEAVE, BAN, APPEAL
    }

    public enum ChannelGovernancePhase {
        CATALOGED,
        RULES_CONSENT_REQUIRED,
        INVITE_OR_APPLY,
        MEMBER_ROLE_ASSIGNED,
        AUTHORIZED_READ_WRITE,
        GOVERNING,
        LEFT,
        BANNED,
        APPEALING,
        ISOLATED
    }

    public enum ChannelRevokeReason {
        LEAVE, BAN, MEMBERSHIP_REVOKED
    }

    public enum ChannelIsolatedAction {
        CHANNEL_READ, CHANNEL_WRITE, GOVERNANCE, LATE_JOIN, HISTORICAL_VOTE_READ, HISTORICAL_VOTE_WRITE, APPEAL
    }

    public enum ChannelIsolatedAdmission {
        ALLOWED, ISOLATED
    }

    public enum ChannelImMutation {
        START, READ, MUTE, SEND
    }

    public enum FriendOnlyAction {
        REMARKS, DIRECT_MESSAGE
    }

    public enum ChannelOnlyAction {
        CHANNEL_READ, CHANNEL_WRITE, GOVERNANCE, INVITE, APPLY, BAN, LEAVE, APPEAL
    }

    public enum ProgressPrivacySurface {
        PROGRESS_PRIVACY, REMARKS, DIRECT_MESSAGE, CHANNEL_MESSAGE
    }

    public enum SocialPermissionDomain {
        CHANNEL, FRIEND, PROGRESS_PRIVACY
    }

    public static final class ChannelAccount4Identity {
        public final int accountId;
        public final long epoch;

        public ChannelAccount4Identity(int accountId, long epoch) {
            if (accountId <= 0) {
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH);
            }
            this.accountId = accountId;
            this.epoch = epoch;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ChannelAccount4Identity)) {
                return false;
            }
            ChannelAccount4Identity that = (ChannelAccount4Identity) o;
            return accountId == that.accountId && epoch == that.epoch;
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountId, epoch);
        }
    }

    /**
     * P04 dual-version CAS. published 可加入；pending 不可加入。expectedVersion 必须等于 published。
     */
    public static final class ChannelRulesCas {
        public final int publishedVersion;
        public final Integer pendingVersion;
        public final int expectedVersion;

        public ChannelRulesCas(int publishedVersion, Integer pendingVersion, int expectedVersion) {
            if (publishedVersion <= 0 || expectedVersion <= 0
                    || (pendingVersion != null && pendingVersion <= publishedVersion)) {
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT);
            }
            this.publishedVersion = publishedVersion;
            this.pendingVersion = pendingVersion;
            this.expectedVersion = expectedVersion;
        }

        public boolean isCurrent() {
            return expectedVersion == publishedVersion;
        }
    }

    public static final class ChannelCatalogEntry {
        public final int channelId;
        public final int conversationId;
        public final ConversationKind kind;
        public final ChannelRulesCas rules;

        public ChannelCatalogEntry(int channelId, int conversationId, ConversationKind kind, ChannelRulesCas rules) {
            if (channelId <= 0 || conversationId <= 0 || kind != ConversationKind.GROUP || rules == null) {
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
            }
            this.channelId = channelId;
            this.conversationId = conversationId;
            this.kind = kind;
            this.rules = rules;
        }
    }

    public static final class ChannelAuthorizationGrant {
        public final ChannelAccount4Identity identity;
        public final int channelId;
        public final int conversationId;
        public final ChannelRole role;
        public final Integer consentedRulesVersion;
        public final long generation;

        public ChannelAuthorizationGrant(ChannelAccount4Identity identity, int channelId, int conversationId,
                ChannelRole role, Integer consentedRulesVersion, long generation) {
            this.identity = identity;
            this.channelId = channelId;
            this.conversationId = conversationId;
            this.role = role;
            this.consentedRulesVersion = consentedRulesVersion;
            this.generation = generation;
        }

        public Set<ChannelCapability> capabilities() {
            return role.capabilities();
        }

        public boolean permits(ChannelCapability capability) {
            return capabilities().contains(capability);
        }
    }

    public static final class ChannelPageLease {
        public final int channelId;
        public final int conversationId;
        public final long generation;
        public final ChannelAccount4Identity identity;
        public final Set<ChannelCapability> capabilities;
        public final Integer consentedRulesVersion;

        public ChannelPageLease(int channelId, int conversationId, long generation, ChannelAccount4Identity identity,
                Set<ChannelCapability> capabilities, Integer consentedRulesVersion) {
            this.channelId = channelId;
            this.conversationId = conversationId;
            this.generation = generation;
            this.identity = identity;
            this.capabilities = capabilities == null || capabilities.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
            this.consentedRulesVersion = consentedRulesVersion;
        }
    }

    public static final class ChannelIsolatedRequest {
        public final ChannelPageLease lease;
        public final ChannelIsolatedAction action;

        public ChannelIsolatedRequest(ChannelPageLease lease, ChannelIsolatedAction action) {
            this.lease = Objects.requireNonNull(lease, "lease");
            this.action = Objects.requireNonNull(action, "action");
        }
    }

    public static final class FriendPermissionGrant {
        public final Integer accountId;

        private FriendPermissionGrant(Integer accountId) {
            this.accountId = accountId;
        }

        public static FriendPermissionGrant none() {
            return new FriendPermissionGrant(null);
        }

        public static FriendPermissionGrant friend(int accountId) {
            return new FriendPermissionGrant(Integer.valueOf(accountId));
        }

        public boolean isFriend() {
            return accountId != null && accountId.intValue() > 0;
        }
    }

    /** Exclusive GroupPoll view. Not a second poll writer. */
    public static final class GroupPollView {
        public final long id;
        public final long conversationId;
        public final boolean open;
        public final Long myOptionId;

        public GroupPollView(long id, long conversationId, boolean open, Long myOptionId) {
            this.id = id;
            this.conversationId = conversationId;
            this.open = open;
            this.myOptionId = myOptionId;
        }

        public boolean isOpen() {
            return open;
        }
    }
}
