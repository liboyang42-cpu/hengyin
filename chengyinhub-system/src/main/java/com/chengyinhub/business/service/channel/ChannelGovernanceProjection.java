package com.chengyinhub.business.service.channel;

import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAdmission;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedRequest;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelPageLease;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRevokeReason;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;

/**
 * C08 独占：创建/目录/规则同意/邀请申请/成员角色 → 读写与治理 → 退出/封禁/申诉。
 * 不拥有公共 IM send/receipt、journal、NativeWebhook*、43/45/46/47。
 *
 * <p>直播成员闸走既有 {@link IImService#isActiveGroupMember}；缺端口 WAITING_SOURCE。
 * 不把 ACCOUNT4 accountId 猜成 memberId。
 */
public final class ChannelGovernanceProjection {

    public static final String IM_ACTIVE_GROUP_MEMBER = "im.isActiveGroupMember";
    public static final String IM_LIST_MESSAGES = "im.listMessages";

    public final ChannelCatalogEntry catalog;
    public final ChannelAccount4Identity identity;
    private ChannelGovernancePhase phase;
    private long generation;
    private Integer consentedRulesVersion;
    private ChannelRole role;
    private ChannelRevokeReason revokeReason;
    private final ChannelMembershipSource membership;
    private final ChannelMessageSource messages;

    public ChannelGovernanceProjection(ChannelCatalogEntry catalog, ChannelAccount4Identity identity) {
        this(catalog, identity, null);
    }

    public ChannelGovernanceProjection(ChannelCatalogEntry catalog, ChannelAccount4Identity identity,
            ChannelMembershipSource membership) {
        this(catalog, identity, membership, null);
    }

    public ChannelGovernanceProjection(ChannelCatalogEntry catalog, ChannelAccount4Identity identity,
            ChannelMembershipSource membership, ChannelMessageSource messages) {
        this.catalog = catalog;
        this.identity = identity;
        this.phase = ChannelGovernancePhase.CATALOGED;
        this.generation = 1L;
        this.consentedRulesVersion = null;
        this.role = ChannelRole.NONE;
        this.revokeReason = null;
        this.membership = membership;
        this.messages = messages;
    }

    public ChannelGovernancePhase phase() {
        return phase;
    }

    public long generation() {
        return generation;
    }

    public Integer consentedRulesVersion() {
        return consentedRulesVersion;
    }

    public ChannelRole role() {
        return role;
    }

    public ChannelRevokeReason revokeReason() {
        return revokeReason;
    }

    public ChannelAuthorizationGrant grant() {
        if (role == ChannelRole.NONE
                || (phase != ChannelGovernancePhase.AUTHORIZED_READ_WRITE
                        && phase != ChannelGovernancePhase.GOVERNING)) {
            return null;
        }
        return new ChannelAuthorizationGrant(identity, catalog.channelId, catalog.conversationId, role,
                consentedRulesVersion, generation);
    }

    public void requireRulesConsent() {
        ensureActive();
        if (phase != ChannelGovernancePhase.CATALOGED && phase != ChannelGovernancePhase.RULES_CONSENT_REQUIRED) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
        }
        phase = ChannelGovernancePhase.RULES_CONSENT_REQUIRED;
        bump();
    }

    public void consentToPublishedRules(ChannelRulesAdmission admission) {
        ensureActive();
        if (phase != ChannelGovernancePhase.RULES_CONSENT_REQUIRED && phase != ChannelGovernancePhase.CATALOGED) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
        }
        consentedRulesVersion = Integer.valueOf(admission.admit(identity, catalog));
        phase = ChannelGovernancePhase.INVITE_OR_APPLY;
        bump();
    }

    public void submitInviteOrApply() {
        ensureActive();
        if (phase != ChannelGovernancePhase.INVITE_OR_APPLY || consentedRulesVersion == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED);
        }
        role = ChannelRole.APPLICANT;
        bump();
    }

    public void assignMemberRole(ChannelRole assigned) {
        ensureActive();
        if (phase != ChannelGovernancePhase.INVITE_OR_APPLY || role != ChannelRole.APPLICANT) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ROLE_REQUIRED);
        }
        if (assigned != ChannelRole.MEMBER && assigned != ChannelRole.MODERATOR && assigned != ChannelRole.OWNER) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ROLE_REQUIRED);
        }
        role = assigned;
        phase = ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED;
        bump();
    }

    public ChannelAuthorizationGrant authorizeReadWrite() {
        ensureActive();
        if (!role.capabilities().contains(ChannelCapability.READ)
                || !role.capabilities().contains(ChannelCapability.WRITE)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ROLE_REQUIRED);
        }
        if (phase != ChannelGovernancePhase.MEMBER_ROLE_ASSIGNED
                && phase != ChannelGovernancePhase.AUTHORIZED_READ_WRITE
                && phase != ChannelGovernancePhase.GOVERNING) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.NOT_MEMBER);
        }
        ChannelMemberAuthorizationProjection.authorize(identity, catalog, role, consentedRulesVersion,
                ChannelCapability.WRITE);
        phase = ChannelGovernancePhase.AUTHORIZED_READ_WRITE;
        bump();
        return grant();
    }

    /**
     * 用既有群成员闸签发读写。缺 {@link IImService#isActiveGroupMember} 为 WAITING_SOURCE。
     * memberId 由调用方提供，不把 ACCOUNT4 accountId 猜成 IM memberId。
     */
    public ChannelAuthorizationGrant authorizeFromMembership(long memberId) {
        if (membership == null) {
            throw new IllegalStateException("WAITING_SOURCE:" + IM_ACTIVE_GROUP_MEMBER);
        }
        if (!membership.isActiveGroupMember(memberId, catalog.conversationId)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.NOT_MEMBER);
        }
        return authorizeReadWrite();
    }

    /**
     * 用既有 {@link IImService#listMessages} 读会话页。先过既有撤权门，再过群成员闸。
     * 缺成员口或消息口为 WAITING_SOURCE。撤权或非成员不调用 listMessages，也不改 phase。
     * 纯读不要求 WRITE。不 send、不 markRead。
     */
    public Map<String, Object> readMessages(long memberId, Long cursorId, int size, Long afterId) {
        if (membership == null) {
            throw new IllegalStateException("WAITING_SOURCE:" + IM_ACTIVE_GROUP_MEMBER);
        }
        if (messages == null) {
            throw new IllegalStateException("WAITING_SOURCE:" + IM_LIST_MESSAGES);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size 不合法");
        }
        ensureActive();
        if (!membership.isActiveGroupMember(memberId, catalog.conversationId)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.NOT_MEMBER);
        }
        Map<String, Object> page = messages.listMessages(memberId, catalog.conversationId, cursorId, size, afterId);
        if (page == null || foreignConversation(page)) {
            return null;
        }
        return page;
    }

    private boolean foreignConversation(Map<String, Object> page) {
        Object echoed = page.get("conversationId");
        if (echoed instanceof Number && ((Number) echoed).longValue() != catalog.conversationId) {
            return true;
        }
        Object list = page.get("list");
        if (!(list instanceof List<?> rows)) {
            return false;
        }
        for (Object row : rows) {
            if (row instanceof ImMessage message && message.getConversationId() != null
                    && message.getConversationId().longValue() != catalog.conversationId) {
                return true;
            }
        }
        return false;
    }

    public void govern() {
        authorizeReadWrite();
        ChannelMemberAuthorizationProjection.authorize(identity, catalog, role, consentedRulesVersion,
                ChannelCapability.GOVERN);
        phase = ChannelGovernancePhase.GOVERNING;
        bump();
    }

    public void leave() {
        ensureActive();
        if (!role.capabilities().contains(ChannelCapability.LEAVE)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ROLE_REQUIRED);
        }
        isolate(ChannelRevokeReason.LEAVE, ChannelGovernancePhase.LEFT);
    }

    public void ban() {
        ensureActive();
        isolate(ChannelRevokeReason.BAN, ChannelGovernancePhase.BANNED);
    }

    public void appeal() {
        if (phase != ChannelGovernancePhase.BANNED && phase != ChannelGovernancePhase.APPEALING) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.REVOKED);
        }
        phase = ChannelGovernancePhase.APPEALING;
        bump();
    }

    public ChannelPageLease pageLease() {
        ChannelAuthorizationGrant current = grant();
        if (current == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.NOT_MEMBER);
        }
        return new ChannelPageLease(catalog.channelId, catalog.conversationId, generation, identity,
                role.capabilities(), current.consentedRulesVersion);
    }

    public ChannelIsolatedAdmission admit(ChannelIsolatedRequest request) {
        return new ChannelRevokeIsolation(catalog.channelId, identity,
                revokeReason == null ? null : Long.valueOf(generation), revokeReason, phase).admit(request);
    }

    public void commitUnsignedOriginal(String capability) {
        throw new IllegalStateException("WAITING_SOURCE:" + capability);
    }

    private void isolate(ChannelRevokeReason reason, ChannelGovernancePhase next) {
        revokeReason = reason;
        role = ChannelRole.NONE;
        phase = ChannelGovernancePhase.ISOLATED;
        bump();
        phase = next;
    }

    private void bump() {
        generation += 1L;
    }

    private void ensureActive() {
        if (revokeReason != null || phase == ChannelGovernancePhase.ISOLATED || phase == ChannelGovernancePhase.LEFT
                || phase == ChannelGovernancePhase.BANNED) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.REVOKED);
        }
    }

    /** 只读既有会话页；不 send、不 markRead。 */
    public interface ChannelMessageSource {
        Map<String, Object> listMessages(long memberId, long conversationId, Long cursorId, int size, Long afterId);
    }

    public static final class ImServiceChannelMessages implements ChannelMessageSource {
        private final IImService im;

        public ImServiceChannelMessages(IImService im) {
            this.im = im;
        }

        @Override
        public Map<String, Object> listMessages(long memberId, long conversationId, Long cursorId, int size,
                Long afterId) {
            if (im == null) {
                throw new IllegalStateException("WAITING_SOURCE:" + IM_LIST_MESSAGES);
            }
            return im.listMessages(Long.valueOf(memberId), Long.valueOf(conversationId), cursorId, size, afterId);
        }
    }

    /** 只读既有群成员闸；不发 IM、不改 journal。 */
    public interface ChannelMembershipSource {
        boolean isActiveGroupMember(long memberId, long conversationId);
    }

    public static final class ImServiceChannelMembership implements ChannelMembershipSource {
        private final IImService im;

        public ImServiceChannelMembership(IImService im) {
            this.im = im;
        }

        @Override
        public boolean isActiveGroupMember(long memberId, long conversationId) {
            if (im == null) {
                throw new IllegalStateException("WAITING_SOURCE:" + IM_ACTIVE_GROUP_MEMBER);
            }
            return im.isActiveGroupMember(Long.valueOf(memberId), Long.valueOf(conversationId));
        }
    }
}
