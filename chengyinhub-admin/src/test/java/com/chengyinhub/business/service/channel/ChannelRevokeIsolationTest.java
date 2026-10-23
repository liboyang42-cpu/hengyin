package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCapability;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelGovernancePhase;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedAdmission;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelIsolatedRequest;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelPageLease;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRevokeReason;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.GroupPollView;

class ChannelRevokeIsolationTest {

    @Test
    void testLeftGroupHistoricalVotesStayReadOnly() {
        GroupPollView poll = new GroupPollView(3L, 12L, true, null);
        assertTrue(ChannelRevokeIsolation.voteWriteAllowed(poll, true));
        assertFalse(ChannelRevokeIsolation.voteWriteAllowed(poll, false));
        assertFalse(ChannelRevokeIsolation.voteWriteAllowed(new GroupPollView(3L, 12L, false, null), true));
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelPageLease lease = new ChannelPageLease(21, 12, 2L, identity,
                EnumSet.of(ChannelCapability.READ, ChannelCapability.WRITE), Integer.valueOf(3));
        ChannelRevokeIsolation isolation = new ChannelRevokeIsolation(21, identity, Long.valueOf(4L),
                ChannelRevokeReason.LEAVE, ChannelGovernancePhase.LEFT);
        assertEquals(ChannelIsolatedAdmission.ALLOWED,
                isolation.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.HISTORICAL_VOTE_READ)));
        assertEquals(ChannelAuthorizationException.Code.HISTORICAL_VOTE_WRITE_DENIED,
                assertThrows(ChannelAuthorizationException.class, () -> isolation
                        .admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.HISTORICAL_VOTE_WRITE))).code);
    }

    @Test
    void testOldPageAndLateWriteIsolateAfterLeave() {
        ChannelAccount4Identity identity = new ChannelAccount4Identity(7, 1L);
        ChannelPageLease lease = new ChannelPageLease(21, 12, 2L, identity, EnumSet.of(ChannelCapability.WRITE),
                Integer.valueOf(3));
        ChannelRevokeIsolation isolation = new ChannelRevokeIsolation(21, identity, Long.valueOf(4L),
                ChannelRevokeReason.LEAVE, ChannelGovernancePhase.LEFT);
        assertEquals(ChannelAuthorizationException.Code.ISOLATED_LATE_REQUEST,
                assertThrows(ChannelAuthorizationException.class,
                        () -> isolation.admit(new ChannelIsolatedRequest(lease, ChannelIsolatedAction.CHANNEL_WRITE)))
                        .code);
    }
}
