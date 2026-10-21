package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Statements for the handoff request table. Not a scanned mapper and not a team-table writer. */
public interface RendezvousHandoffMapper {
    Row findByMatch(@Param("matchId") String matchId);

    int insert(RendezvousHandoffRequest request);

    int update(RendezvousHandoffRequest request);

    int markDispatched(@Param("requestId") String requestId, @Param("updatedAt") long updatedAt);

    final class Row {
        public String requestId;
        public String matchId;
        public String phase;
        public String blocker;
        public long firstActor;
        public long secondActor;
        public long activityId;
        public long createdAt;
        public long updatedAt;
        public Long teamId;
        public int dispatched;

        RendezvousHandoffRequest value() {
            return new RendezvousHandoffRequest(requestId, matchId, firstActor, secondActor, activityId,
                    RendezvousHandoffRequest.Phase.valueOf(phase), teamId, blocker, dispatched == 1, createdAt, updatedAt);
        }
    }
}
