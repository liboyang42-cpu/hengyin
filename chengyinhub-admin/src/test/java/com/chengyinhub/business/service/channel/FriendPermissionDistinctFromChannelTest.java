package com.chengyinhub.business.service.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAuthorizationGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelImMutation;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelOnlyAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRole;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendOnlyAction;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.FriendPermissionGrant;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ProgressPrivacySurface;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.SocialPermissionDomain;

class FriendPermissionDistinctFromChannelTest {

    private ChannelAuthorizationGrant memberGrant() {
        return new ChannelAuthorizationGrant(new ChannelAccount4Identity(7, 1L), 21, 12, ChannelRole.MEMBER,
                Integer.valueOf(3), 2L);
    }

    @Test
    void testChannelGrantCannotSatisfyFriendRemarksOrDM() {
        ChannelAuthorizationGrant grant = memberGrant();
        assertEquals(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, () -> FriendPermissionDistinctFromChannel
                        .authorize(grant, FriendPermissionGrant.none(), FriendOnlyAction.REMARKS)).code);
        assertEquals(ChannelAuthorizationException.Code.FRIEND_PERMISSION_REQUIRED,
                assertThrows(ChannelAuthorizationException.class, () -> FriendPermissionDistinctFromChannel
                        .authorize(grant, FriendPermissionGrant.none(), FriendOnlyAction.DIRECT_MESSAGE)).code);
        FriendPermissionDistinctFromChannel.authorize(grant, FriendPermissionGrant.friend(82),
                FriendOnlyAction.DIRECT_MESSAGE);
        FriendPermissionDistinctFromChannel.authorize(grant, FriendPermissionGrant.none(),
                ChannelOnlyAction.CHANNEL_READ);
        assertEquals(SocialPermissionDomain.FRIEND, FriendPermissionDistinctFromChannel.domain(ChannelImMutation.START));
        assertEquals(SocialPermissionDomain.CHANNEL, FriendPermissionDistinctFromChannel.domain(ChannelImMutation.SEND));
    }

    @Test
    void testP44ProgressPrivacyIsNotRemarksOrDM() {
        ChannelAuthorizationGrant grant = memberGrant();
        assertEquals(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED,
                assertThrows(ChannelAuthorizationException.class, () -> FriendPermissionDistinctFromChannel
                        .discloseProgress(grant, FriendPermissionGrant.friend(82), ProgressPrivacySurface.REMARKS)).code);
        assertEquals(ChannelAuthorizationException.Code.PROGRESS_PRIVACY_DENIED,
                assertThrows(ChannelAuthorizationException.class,
                        () -> FriendPermissionDistinctFromChannel.discloseProgress(grant,
                                FriendPermissionGrant.friend(82), ProgressPrivacySurface.DIRECT_MESSAGE)).code);
        FriendPermissionDistinctFromChannel.discloseProgress(grant, FriendPermissionGrant.none(),
                ProgressPrivacySurface.PROGRESS_PRIVACY);
    }
}
