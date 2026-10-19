package com.chengyinhub.business.city.read;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable allowlisted read-model values, NOT CityContracts/runtime facts or HTTP input DTOs. */
public final class CityPlayerReadModel {
    private CityPlayerReadModel() { }
    public enum Current { AVAILABLE, NO_CURRENT_BOARD }
    public enum Membership { UNAVAILABLE, NOT_JOINED, JOINED }
    public enum Points { UNAVAILABLE, AVAILABLE }

    public static final class Board {
        public final String scope, gameId, boardId, regionId, seasonId, rulesReleaseId, rulesHash;
        public final String lifecycle, title;
        public final long revision;
        public Board(String scope, String gameId, String boardId, String regionId, String seasonId,
                     String rulesReleaseId, String rulesHash, String lifecycle, String title, long revision) {
            this.scope = scope; this.gameId = gameId; this.boardId = boardId; this.regionId = regionId;
            this.seasonId = seasonId; this.rulesReleaseId = rulesReleaseId; this.rulesHash = rulesHash;
            this.lifecycle = lifecycle; this.title = title; this.revision = revision;
        }
    }

    public static final class Participation {
        public final long memberId, membershipVersion, projectionRevision;
        public final String boardId, seasonId, rulesReleaseId, participationId;
        public Participation(long memberId, long membershipVersion, String boardId, String seasonId,
                             String participationId, String rulesReleaseId, long projectionRevision) {
            this.memberId = memberId; this.membershipVersion = membershipVersion;
            this.boardId = boardId; this.seasonId = seasonId; this.participationId = participationId;
            this.rulesReleaseId = rulesReleaseId; this.projectionRevision = projectionRevision;
        }
    }

    /** Approved public catalogue coordinates only. No owner/team ID or activity timestamp. */
    public static final class Point {
        public final String pointId, boardId, seasonId, rulesReleaseId, title;
        public final long projectionRevision;
        public final double latitude, longitude;
        public final boolean mine;
        public Point(String pointId, String boardId, String seasonId, String title,
                     double latitude, double longitude, boolean mine, String rulesReleaseId, long projectionRevision) {
            this.pointId = pointId; this.boardId = boardId; this.seasonId = seasonId;
            this.title = title; this.latitude = latitude; this.longitude = longitude; this.mine = mine;
            this.rulesReleaseId = rulesReleaseId; this.projectionRevision = projectionRevision;
        }
    }

    /** A bounded COMPLETE point projection, not a silently truncated partial page. */
    public static final class Snapshot {
        public final long memberId, sessionEpoch;
        public final String regionId;
        public final Current current;
        public final Board board;
        public final Membership membership;
        public final Participation participation;
        public final Points pointStatus;
        public final List<Point> points;
        public Snapshot(long memberId, long sessionEpoch, String regionId, Current current, Board board,
                        Membership membership, Participation participation, Points pointStatus, List<Point> points) {
            this.memberId = memberId; this.sessionEpoch = sessionEpoch; this.regionId = regionId;
            this.current = current; this.board = board; this.membership = membership;
            this.participation = participation; this.pointStatus = pointStatus;
            this.points = points == null ? null : Collections.unmodifiableList(new ArrayList<Point>(points));
        }
    }
}
