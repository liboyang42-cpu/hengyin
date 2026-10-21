package com.chengyinhub.business.service.rendezvous;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import static com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.Meeting;

/** Statements for the W16 protocol tables. Not an authorization API and not a scanned mapper bean. */
public interface RendezvousProtocolMapper {
    RendezvousVersionedPolicy.Row policy(@Param("dimension") String dimension, @Param("versionId") String versionId);
    Integer contacts(@Param("actor") long actor);
    int insertContact(@Param("actor") long actor);
    int bumpContact(@Param("actor") long actor);
    List<String> waitingIds(@Param("pool") String pool, @Param("now") long now, @Param("limit") int limit);
    Long actorOf(@Param("ticketId") String ticketId);

    MeetingRow meeting(@Param("matchId") String matchId);
    int insertMeeting(Meeting meeting);
    int updateMeeting(Meeting meeting);

    String roleClue(@Param("ticketId") String ticketId);
    int insertRole(@Param("ticketId") String ticketId, @Param("actor") long actor,
                   @Param("matchId") String matchId, @Param("roleClue") String roleClue);

    Integer reportExists(@Param("reporter") long reporter, @Param("reported") long reported);
    int insertReport(@Param("reporter") long reporter, @Param("reported") long reported, @Param("createdAt") long createdAt);

    final class MeetingRow {
        public String matchId, challengeId, state;
        public long activityId, issuedAt, expiresAt;
        public Long firstConfirmedAt, secondConfirmedAt, teamId;
        Meeting value() {
            return new Meeting(matchId, challengeId, activityId, issuedAt, expiresAt,
                    firstConfirmedAt, secondConfirmedAt, teamId,
                    RendezvousMeetingService.State.valueOf(state));
        }
    }
}
