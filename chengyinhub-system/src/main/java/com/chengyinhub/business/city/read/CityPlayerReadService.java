package com.chengyinhub.business.city.read;

import java.util.*;
import static com.chengyinhub.business.city.read.CityPlayerReadModel.*;

/** Read-only privacy projection. No runtime/ledger dependency and no default authority provider. */
public final class CityPlayerReadService {
    public static final int MAX_POINTS = 200;
    private static final long MAX_JSON_INTEGER = 9007199254740991L;
    private final CityPlayerReadRepository repository;
    public CityPlayerReadService(CityPlayerReadRepository repository) { this.repository = repository; }
    public enum View { BOARD, PARTICIPATION, POINTS }
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    /** Expected board identity is required after the current-board read. Never accepts caller facts. */
    public Map<String, Object> read(long memberId, long sessionEpoch, String regionId, View view,
                                    String boardId, String seasonId, String releaseId, Long revision) {
        if (memberId <= 0 || sessionEpoch < 0) throw new Failure("AUTH_REQUIRED");
        if (!id(regionId) || view == null) throw new Failure("INVALID_REQUEST");
        if (view != View.BOARD && (!id(boardId) || !id(seasonId) || !id(releaseId)
                || revision == null || !version(revision))) throw new Failure("INVALID_REQUEST");
        if (repository == null) throw new Failure("CITY_READ_UNAVAILABLE");
        Snapshot snapshot;
        try { snapshot = repository.read(memberId, sessionEpoch, regionId); }
        catch (RuntimeException unavailable) { throw new Failure("CITY_READ_UNAVAILABLE"); }
        validate(snapshot, memberId, sessionEpoch, regionId);
        if (snapshot.current == Current.NO_CURRENT_BOARD) {
            if (view != View.BOARD) throw new Failure("CITY_SNAPSHOT_CHANGED");
            return object("contract", "CITY_PLAYER_READ_V1", "scope", "CITY", "status", "NO_CURRENT_BOARD", "regionId", regionId);
        }
        Board board = snapshot.board;
        if (view != View.BOARD && (!board.boardId.equals(boardId) || !board.seasonId.equals(seasonId)
                || !board.rulesReleaseId.equals(releaseId) || board.revision != revision.longValue()))
            throw new Failure("CITY_SNAPSHOT_CHANGED");
        Map<String, Object> result = object("contract", "CITY_PLAYER_READ_V1", "scope", "CITY",
                "status", "AVAILABLE", "board", project(board));
        if (view == View.BOARD) {
            result.put("participationStatus", snapshot.membership.name());
            result.put("pointsStatus", snapshot.pointStatus.name());
        } else if (view == View.PARTICIPATION) {
            if (snapshot.membership == Membership.UNAVAILABLE) throw new Failure("CITY_PARTICIPATION_UNAVAILABLE");
            result.put("participationStatus", snapshot.membership.name());
            if (snapshot.participation != null) result.put("participation", object(
                    "participationId", snapshot.participation.participationId,
                    "membershipVersion", snapshot.participation.membershipVersion));
        } else {
            if (snapshot.pointStatus == Points.UNAVAILABLE) throw new Failure("CITY_POINTS_UNAVAILABLE");
            List<Map<String, Object>> points = new ArrayList<Map<String, Object>>();
            for (Point point : snapshot.points) points.add(object("pointId", point.pointId, "title", point.title,
                    "latitude", point.latitude, "longitude", point.longitude, "mine", point.mine));
            result.put("points", points);
            result.put("complete", true);
        }
        return result;
    }

    private static void validate(Snapshot s, long actor, long epoch, String region) {
        require(s != null && s.memberId == actor && s.sessionEpoch == epoch && region.equals(s.regionId)
                && s.current != null && s.membership != null && s.pointStatus != null && s.points != null);
        if (s.current == Current.NO_CURRENT_BOARD) {
            require(s.board == null && s.membership == Membership.UNAVAILABLE && s.participation == null
                    && s.pointStatus == Points.UNAVAILABLE && s.points.isEmpty());
            return;
        }
        Board b = s.board;
        require(b != null && "CITY".equals(b.scope) && id(b.gameId) && id(b.boardId) && region.equals(b.regionId)
                && id(b.seasonId) && id(b.rulesReleaseId) && b.rulesHash != null
                && b.rulesHash.matches("[0-9a-f]{64}") && version(b.revision) && label(b.title)
                && Arrays.asList("OPEN", "FROZEN", "CLOSED").contains(b.lifecycle));
        if (s.membership == Membership.JOINED) {
            Participation p = s.participation;
            require(p != null && p.memberId == actor && version(p.membershipVersion)
                    && b.boardId.equals(p.boardId) && b.seasonId.equals(p.seasonId) && id(p.participationId)
                    && b.rulesReleaseId.equals(p.rulesReleaseId) && b.revision == p.projectionRevision);
        } else require(s.participation == null);
        require(s.points.size() <= MAX_POINTS);
        if (s.pointStatus == Points.UNAVAILABLE) require(s.points.isEmpty());
        else require(s.membership != Membership.UNAVAILABLE);
        Set<String> seen = new HashSet<String>();
        for (Point p : s.points) require(p != null && id(p.pointId) && seen.add(p.pointId)
                && b.boardId.equals(p.boardId) && b.seasonId.equals(p.seasonId) && label(p.title)
                && b.rulesReleaseId.equals(p.rulesReleaseId) && b.revision == p.projectionRevision
                && Double.isFinite(p.latitude) && p.latitude >= -90 && p.latitude <= 90
                && Double.isFinite(p.longitude) && p.longitude >= -180 && p.longitude <= 180
                && (!p.mine || s.membership == Membership.JOINED));
    }
    private static Map<String, Object> project(Board b) {
        return object("gameId", b.gameId, "boardId", b.boardId, "regionId", b.regionId, "seasonId", b.seasonId,
                "rulesReleaseId", b.rulesReleaseId, "rulesHash", b.rulesHash, "lifecycle", b.lifecycle,
                "title", b.title, "revision", b.revision);
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}"); }
    private static boolean version(long value) { return value >= 0 && value <= MAX_JSON_INTEGER; }
    private static boolean label(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 160) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }
    private static void require(boolean value) { if (!value) throw new Failure("CITY_READ_UNAVAILABLE"); }
    private static Map<String, Object> object(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
