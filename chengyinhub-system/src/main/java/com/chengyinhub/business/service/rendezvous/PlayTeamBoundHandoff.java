package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.PlayTeamHandoffRequest;
import com.chengyinhub.business.service.IPlayTeamService;
import java.util.Objects;

/** Calls the play-team proxy. It does not open its own transaction. */
public final class PlayTeamBoundHandoff implements PlayTeamHandoffGateway {
    private final IPlayTeamService teams;

    public PlayTeamBoundHandoff(IPlayTeamService teams) {
        this.teams = Objects.requireNonNull(teams);
    }

    public boolean enabled() { return true; }

    public RendezvousHandoffTeamPort.Lookup lookup(String requestId) {
        PlayTeamHandoffRequest.Fact fact = teams.findHandoff(NAMESPACE, requestId);
        if (fact.kind == PlayTeamHandoffRequest.Fact.Kind.ABSENT) return RendezvousHandoffTeamPort.Lookup.absent();
        if (fact.kind == PlayTeamHandoffRequest.Fact.Kind.UNKNOWN || fact.teamId == null || fact.teamId <= 0) {
            return RendezvousHandoffTeamPort.Lookup.unknown("TEAM_QUERY_UNKNOWN");
        }
        if (fact.kind == PlayTeamHandoffRequest.Fact.Kind.ENDED || fact.kind == PlayTeamHandoffRequest.Fact.Kind.LEFT) {
            return RendezvousHandoffTeamPort.Lookup.unknown("TEAM_MEMBERSHIP_ENDED");
        }
        RendezvousHandoffTeamPort.Status status = fact.kind == PlayTeamHandoffRequest.Fact.Kind.JOINED && fact.peerActive
                ? RendezvousHandoffTeamPort.Status.JOINED : RendezvousHandoffTeamPort.Status.TEAM_CREATED;
        if (fact.namespace == null || fact.requestId == null || fact.matchId == null
                || fact.leaderMemberId == null || fact.peerMemberId == null || fact.activityId == null) {
            return RendezvousHandoffTeamPort.Lookup.unknown("TEAM_QUERY_UNKNOWN");
        }
        return RendezvousHandoffTeamPort.Lookup.carried(status, fact.teamId, fact.namespace, fact.requestId, fact.matchId,
                fact.leaderMemberId, fact.peerMemberId, fact.activityId);
    }

    public long create(RendezvousHandoffRequest request) {
        PlayTeamHandoffRequest.Fact fact = teams.createForHandoff(row(request));
        if (fact.teamId == null || fact.teamId <= 0) throw new IllegalStateException("CREATE_RESULT_UNKNOWN");
        return fact.teamId;
    }

    public boolean join(RendezvousHandoffRequest request) {
        PlayTeamHandoffRequest.Fact fact = teams.joinForHandoff(row(request));
        return fact.kind == PlayTeamHandoffRequest.Fact.Kind.JOINED && fact.peerActive
                && fact.teamStatus != null && fact.teamStatus < PlayTeam.STATUS_ENDED;
    }

    private static PlayTeamHandoffRequest row(RendezvousHandoffRequest request) {
        PlayTeamHandoffRequest row = new PlayTeamHandoffRequest();
        row.setNamespace(NAMESPACE);
        row.setRequestId(request.requestId);
        row.setMatchId(request.matchId);
        row.setLeaderMemberId(request.firstActor);
        row.setPeerMemberId(request.secondActor);
        row.setActivityId(request.activityId);
        return row;
    }
}
