package com.chengyinhub.business.service.channel;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelImMutation;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelOnlyAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendOnlyAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendPermissionGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ProgressPrivacySurface;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.SocialPermissionDomain;

/**
 * C08 独占：频道授权不能冒充好友权限。P44 有效进度隐私不能用备注或已有私聊替代。
 */
public final class FriendPermissionDistinctFromChannel {

    private FriendPermissionDistinctFromChannel() {
    }

    public static void authorize(ChannelAuthorizationGrant channel, FriendPermissionGrant friend,
            FriendOnlyAction action) {
        // channel grant is ignored for friend-only actions
        if (channel != null && action == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED);
        }
        if (action == FriendOnlyAction.REMARKS || action == FriendOnlyAction.DIRECT_MESSAGE) {
            if (friend == null || !friend.isFriend()) {
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED);
            }
        }
    }

    public static void authorize(ChannelAuthorizationGrant channel, FriendPermissionGrant friend,
            ChannelOnlyAction action) {
        if (friend != null && channel == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
        }
        if (channel == null) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
        }
        ChannelCapability capability;
        switch (action) {
            case CHANNEL_READ:
                capability = ChannelCapability.READ;
                break;
            case CHANNEL_WRITE:
                capability = ChannelCapability.WRITE;
                break;
            case GOVERNANCE:
                capability = ChannelCapability.GOVERN;
                break;
            case INVITE:
                capability = ChannelCapability.INVITE;
                break;
            case APPLY:
                capability = ChannelCapability.APPLY;
                break;
            case BAN:
                capability = ChannelCapability.BAN;
                break;
            case LEAVE:
                capability = ChannelCapability.LEAVE;
                break;
            case APPEAL:
                capability = ChannelCapability.APPEAL;
                break;
            default:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
        }
        if (!channel.permits(capability)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.CHANNEL_PERMISSION_REQUIRED);
        }
    }

    public static void discloseProgress(ChannelAuthorizationGrant channel, FriendPermissionGrant friend,
            ProgressPrivacySurface surface) {
        switch (surface) {
            case REMARKS:
            case DIRECT_MESSAGE:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED);
            case CHANNEL_MESSAGE:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED);
            case PROGRESS_PRIVACY:
                if (channel == null || !channel.permits(ChannelCapability.READ)) {
                    throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED);
                }
                return;
            default:
                throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED);
        }
    }

    public static SocialPermissionDomain domain(ChannelImMutation mutation) {
        if (mutation == ChannelImMutation.START) {
            return SocialPermissionDomain.FRIEND;
        }
        return SocialPermissionDomain.CHANNEL;
    }
}
