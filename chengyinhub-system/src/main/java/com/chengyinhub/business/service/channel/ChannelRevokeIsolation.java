package com.chengyinhub.business.service.channel;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAdmission;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedRequest;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelPageLease;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRevokeReason;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.GroupPollView;

/**
 * C08 独占：撤权后旧页和迟到请求隔离。退出群历史投票只读按原 GroupPoll 例外保留，不抹合法历史。
 */
public final class ChannelRevokeIsolation {

    public final int channelId;
    public final ChannelAccount4Identity identity;
    public final Long revokedAtGeneration;
    public final ChannelRevokeReason reason;
    public final ChannelGovernancePhase phase;

    public ChannelRevokeIsolation(int channelId, ChannelAccount4Identity identity, Long revokedAtGeneration,
            ChannelRevokeReason reason, ChannelGovernancePhase phase) {
        this.channelId = channelId;
        this.identity = identity;
        this.revokedAtGeneration = revokedAtGeneration;
        this.reason = reason;
        this.phase = phase;
    }

    public ChannelIsolatedAdmission admit(ChannelIsolatedRequest request) {
        ChannelPageLease lease = request.lease;
        if (lease.channelId != channelId || !identity.equals(lease.identity)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH);
        }
        if (request.action == ChannelIsolatedAction.HISTORICAL_VOTE_READ) {
            return historicalVoteRead(lease);
        }
        if (request.action == ChannelIsolatedAction.HISTORICAL_VOTE_WRITE) {
            if (reason == null) {
                return ChannelIsolatedAdmission.ALLOWED;
            }
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.HISTORICAL_VOTE_WRITE_DENIED);
        }
        if (request.action == ChannelIsolatedAction.APPEAL) {
            if (reason == ChannelRevokeReason.BAN
                    && (phase == ChannelGovernancePhase.BANNED || phase == ChannelGovernancePhase.APPEALING)) {
                return ChannelIsolatedAdmission.ALLOWED;
            }
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.REVOKED);
        }
        if (revokedAtGeneration == null) {
            return ChannelIsolatedAdmission.ALLOWED;
        }
        if (lease.generation <= revokedAtGeneration.longValue()
                || request.action == ChannelIsolatedAction.LATE_JOIN) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ISOLATED_LATE_REQUEST);
        }
        throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.REVOKED);
    }

    public ChannelIsolatedAdmission historicalVoteAccess(GroupPollView poll, boolean membershipActive) {
        if (poll.id <= 0 || poll.conversationId <= 0) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
        }
        return ChannelIsolatedAdmission.ALLOWED;
    }

    /**
     * Write votes require current membership. After revoke, original exception keeps
     * result reads and rejects new votes without wiping history.
     */
    public static boolean voteWriteAllowed(GroupPollView poll, boolean membershipActive) {
        return membershipActive && poll.isOpen() && poll.myOptionId == null;
    }

    private ChannelIsolatedAdmission historicalVoteRead(ChannelPageLease lease) {
        if (lease.conversationId <= 0) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.INVALID_CATALOG);
        }
        return ChannelIsolatedAdmission.ALLOWED;
    }
}
