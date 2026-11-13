package com.chengyinhub.business.mapper.friend;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/** 好友频道邀请（friend_channel_invite）与好友入队邀请（friend_team_offer）读写原语。 */
public interface FriendInviteMapper
{
    int insertChannelInvite(@Param("inviterId") long inviterId, @Param("peerId") long peerId, @Param("channelId") long channelId,
            @Param("guildKind") int guildKind, @Param("guildId") long guildId, @Param("requestId") String requestId);

    Map<String, Object> selectChannelInvite(@Param("id") long id);

    Map<String, Object> lockChannelInvite(@Param("id") long id);

    List<Map<String, Object>> listBatch(@Param("inviterId") long inviterId, @Param("requestId") String requestId);

    int setChannelInviteState(@Param("id") long id, @Param("state") String state);

    List<Map<String, Object>> listMyChannelInvites(@Param("inviterId") long inviterId, @Param("before") long before);

    int countClubPending(@Param("clubId") long clubId, @Param("memberId") long memberId);

    int insertOffer(@Param("offerId") String offerId, @Param("teamId") long teamId, @Param("inviterId") long inviterId,
            @Param("recipientId") long recipientId, @Param("joinMode") int joinMode, @Param("requestId") String requestId);

    Map<String, Object> selectOffer(@Param("offerId") String offerId);

    Map<String, Object> lockOffer(@Param("offerId") String offerId);

    int setOfferState(@Param("offerId") String offerId, @Param("state") String state);

    List<Map<String, Object>> listMyOffers(@Param("memberId") long memberId, @Param("before") long before);

    Map<String, Object> selectTeam(@Param("teamId") long teamId);

    int countTeamMember(@Param("teamId") long teamId, @Param("memberId") long memberId);

    String selectClubName(@Param("clubId") long clubId);
}
