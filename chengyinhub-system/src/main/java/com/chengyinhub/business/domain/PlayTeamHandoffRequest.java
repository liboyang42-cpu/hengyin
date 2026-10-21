package com.chengyinhub.business.domain;

/** Durable handoff index for one real team. Not a shadow team and not an invite. */
public class PlayTeamHandoffRequest {
    public static final String PHASE_CREATED = "TEAM_CREATED";
    public static final String PHASE_JOINED = "JOINED";

    private String namespace;
    private String requestId;
    private String matchId;
    private Long leaderMemberId;
    private Long peerMemberId;
    private Long activityId;
    private Long teamId;
    private String phase;

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getMatchId() { return matchId; }
    public void setMatchId(String matchId) { this.matchId = matchId; }
    public Long getLeaderMemberId() { return leaderMemberId; }
    public void setLeaderMemberId(Long leaderMemberId) { this.leaderMemberId = leaderMemberId; }
    public Long getPeerMemberId() { return peerMemberId; }
    public void setPeerMemberId(Long peerMemberId) { this.peerMemberId = peerMemberId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }

    /** Lookup or command result. Invite codes are not on this type. */
    public static final class Fact {
        public enum Kind { ABSENT, TEAM_CREATED, JOINED, LEFT, ENDED, UNKNOWN }

        public final Kind kind;
        public final Long teamId;
        public final Integer teamStatus;
        public final boolean peerActive;
        public final String phase;
        public final String namespace;
        public final String requestId;
        public final String matchId;
        public final Long leaderMemberId;
        public final Long peerMemberId;
        public final Long activityId;

        private Fact(Kind kind, Long teamId, Integer teamStatus, boolean peerActive, String phase,
                     String namespace, String requestId, String matchId,
                     Long leaderMemberId, Long peerMemberId, Long activityId) {
            this.kind = kind;
            this.teamId = teamId;
            this.teamStatus = teamStatus;
            this.peerActive = peerActive;
            this.phase = phase;
            this.namespace = namespace;
            this.requestId = requestId;
            this.matchId = matchId;
            this.leaderMemberId = leaderMemberId;
            this.peerMemberId = peerMemberId;
            this.activityId = activityId;
        }

        public static Fact absent() { return new Fact(Kind.ABSENT, null, null, false, null, null, null, null, null, null, null); }
        public static Fact unknown() { return new Fact(Kind.UNKNOWN, null, null, false, null, null, null, null, null, null, null); }
        public static Fact of(Kind kind, Long teamId, Integer teamStatus, boolean peerActive, String phase) {
            return new Fact(kind, teamId, teamStatus, peerActive, phase, null, null, null, null, null, null);
        }
        public static Fact bound(Kind kind, Long teamId, Integer teamStatus, boolean peerActive, String phase,
                                 PlayTeamHandoffRequest row) {
            return new Fact(kind, teamId, teamStatus, peerActive, phase, row.getNamespace(), row.getRequestId(),
                    row.getMatchId(), row.getLeaderMemberId(), row.getPeerMemberId(), row.getActivityId());
        }
    }
}
