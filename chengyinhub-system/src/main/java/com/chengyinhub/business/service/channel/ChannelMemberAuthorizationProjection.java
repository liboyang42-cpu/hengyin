package com.chengyinhub.business.service.channel;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelImMutation;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ConversationKind;

/**
 * C08 独占：成员授权签发。绑定 P38 ACCOUNT4 + 目录 group + 已同意的 P04 published 版本。
 * 频道读写不能冒充好友授权。existing member-authorization 合同先消费，不报成缺代码。
 */
public final class ChannelMemberAuthorizationProjection {

    private ChannelMemberAuthorizationProjection() {
    }

    public static void authorize(ChannelAccount4Identity identity, ChannelCatalogEntry catalog, ChannelRole role,
            Integer consentedRulesVersion, ChannelCapability capability) {
        if (catalog.kind != ConversationKind.GROUP) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
        }
        if (role == ChannelRole.NONE) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.NOT_MEMBER);
        }
        if (consentedRulesVersion == null || consentedRulesVersion.intValue() != catalog.rules.publishedVersion
                || !catalog.rules.isCurrent()) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED);
        }
        if (!role.capabilities().contains(capability)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ROLE_REQUIRED);
        }
        if (identity == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH);
        }
    }

    /** Channel rw never becomes a friend grant. */
    public static void denyFriendImpersonation(ChannelAuthorizationGrant grant, ChannelImMutation mutation) {
        switch (mutation) {
            case START:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED);
            case READ:
            case MUTE:
                if (!grant.permits(ChannelCapability.READ)) {
                    throw new ChannelAuthorizationException(
                            ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
                }
                return;
            case SEND:
                if (!grant.permits(ChannelCapability.WRITE)) {
                    throw new ChannelAuthorizationException(
                            ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
                }
                return;
            default:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
        }
    }
}
