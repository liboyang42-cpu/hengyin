package com.chengyinhub.business.city.read;

import com.alibaba.fastjson2.JSON;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.city.read.CityPlayerReadModel.*;

class CityPlayerReadServiceTest {
    static Board board() { return new Board("CITY", "official-city", "board-1", "region-1", "season-1",
            "release-1", String.join("", Collections.nCopies(64, "a")), "OPEN", "City board", 7); }
    static Participation participation() { return new Participation(41, 3, "board-1", "season-1", "participation-1", "release-1", 7); }
    static Point point(String id, boolean mine) { return new Point(id, "board-1", "season-1", "Public catalogue point", 31, 121, mine, "release-1", 7); }
    static Snapshot ready() { return snapshot(board(), Membership.JOINED, participation(), Points.AVAILABLE,
            Arrays.asList(point("point-1", true), point("point-2", false))); }
    static Snapshot snapshot(Board b, Membership m, Participation p, Points status, List<Point> points) {
        return new Snapshot(41, 2, "region-1", Current.AVAILABLE, b, m, p, status, points);
    }
    static CityPlayerReadService service(Snapshot s) { return new CityPlayerReadService((actor, epoch, region) -> s); }
    static Map<String, Object> read(CityPlayerReadService service, CityPlayerReadService.View view) {
        return service.read(41, 2, "region-1", view, "board-1", "season-1", "release-1", 7L);
    }
    static void failure(String code, Runnable work) { assertEquals(code, assertThrows(CityPlayerReadService.Failure.class, work::run).code); }

    @Test void missingProviderAndProviderFailureAreUnavailableNotEmpty() {
        failure("CITY_READ_UNAVAILABLE", () -> read(new CityPlayerReadService(null), CityPlayerReadService.View.BOARD));
        failure("CITY_READ_UNAVAILABLE", () -> read(new CityPlayerReadService((a, e, r) -> { throw new IllegalStateException("private data"); }), CityPlayerReadService.View.BOARD));
    }
    @Test void authoritativeNoCurrentBoardHasDistinctResult() {
        Snapshot absent = new Snapshot(41, 2, "region-1", Current.NO_CURRENT_BOARD, null,
                Membership.UNAVAILABLE, null, Points.UNAVAILABLE, Collections.emptyList());
        assertEquals("NO_CURRENT_BOARD", read(service(absent), CityPlayerReadService.View.BOARD).get("status"));
        failure("CITY_SNAPSHOT_CHANGED", () -> read(service(absent), CityPlayerReadService.View.POINTS));
    }
    @Test void validatesActorEpochAndRegionBeforeDispatch() {
        AtomicInteger calls = new AtomicInteger();
        CityPlayerReadService s = new CityPlayerReadService((a, e, r) -> { calls.incrementAndGet(); return ready(); });
        failure("AUTH_REQUIRED", () -> s.read(0, 2, "region-1", CityPlayerReadService.View.BOARD, null, null, null, null));
        failure("AUTH_REQUIRED", () -> s.read(41, -1, "region-1", CityPlayerReadService.View.BOARD, null, null, null, null));
        failure("INVALID_REQUEST", () -> s.read(41, 2, "../region", CityPlayerReadService.View.BOARD, null, null, null, null));
        assertEquals(0, calls.get());
    }
    @Test void sourceActorEpochRegionMismatchFailClosed() {
        for (Snapshot s : Arrays.asList(
                new Snapshot(42, 2, "region-1", Current.AVAILABLE, board(), Membership.JOINED, participation(), Points.AVAILABLE, Collections.emptyList()),
                new Snapshot(41, 3, "region-1", Current.AVAILABLE, board(), Membership.JOINED, participation(), Points.AVAILABLE, Collections.emptyList()),
                new Snapshot(41, 2, "region-2", Current.AVAILABLE, board(), Membership.JOINED, participation(), Points.AVAILABLE, Collections.emptyList())))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(s), CityPlayerReadService.View.BOARD));
    }
    @Test void boardReadCarriesOnlyAllowlistedPermanentIdentityAndReadStatus() {
        Map<String, Object> result = read(service(ready()), CityPlayerReadService.View.BOARD);
        assertEquals(new HashSet<>(Arrays.asList("contract", "scope", "status", "board", "participationStatus", "pointsStatus")), result.keySet());
        String json = JSON.toJSONString(result);
        assertTrue(json.contains("official-city")); assertTrue(json.contains("CITY"));
        for (String forbidden : Arrays.asList("memberId", "teamId", "receipt", "proof", "payer", "token", "acceptedAt", "activityId", "topicId"))
            assertFalse(json.contains(forbidden), forbidden);
    }
    @Test void ownParticipationContainsNoActorOrTeamIdentity() {
        Map<String, Object> result = read(service(ready()), CityPlayerReadService.View.PARTICIPATION);
        Map<?, ?> p = (Map<?, ?>) result.get("participation");
        assertEquals(new HashSet<>(Arrays.asList("participationId", "membershipVersion")), p.keySet());
    }
    @Test void authoritativeNotJoinedDiffersFromUnavailable() {
        Snapshot absent = snapshot(board(), Membership.NOT_JOINED, null, Points.AVAILABLE, Collections.singletonList(point("point-1", false)));
        assertEquals("NOT_JOINED", read(service(absent), CityPlayerReadService.View.PARTICIPATION).get("participationStatus"));
        Snapshot unknown = snapshot(board(), Membership.UNAVAILABLE, null, Points.UNAVAILABLE, Collections.emptyList());
        assertEquals("UNAVAILABLE", read(service(unknown), CityPlayerReadService.View.BOARD).get("participationStatus"));
        failure("CITY_PARTICIPATION_UNAVAILABLE", () -> read(service(unknown), CityPlayerReadService.View.PARTICIPATION));
        failure("CITY_POINTS_UNAVAILABLE", () -> read(service(unknown), CityPlayerReadService.View.POINTS));
    }
    @Test void pointsExposeOnlySafeCatalogueAndCallerOwnership() {
        Map<String, Object> result = read(service(ready()), CityPlayerReadService.View.POINTS);
        List<?> rows = (List<?>) result.get("points"); assertEquals(2, rows.size());
        assertEquals(new HashSet<>(Arrays.asList("pointId", "title", "latitude", "longitude", "mine")), ((Map<?, ?>) rows.get(0)).keySet());
        assertEquals(true, ((Map<?, ?>) rows.get(0)).get("mine")); assertEquals(false, ((Map<?, ?>) rows.get(1)).get("mine"));
        assertEquals(true, result.get("complete"));
    }
    @Test void boardSeasonReleaseRevisionMismatchRequiresNewSnapshot() {
        for (String[] ids : Arrays.asList(new String[]{"board-2", "season-1", "release-1"},
                new String[]{"board-1", "season-2", "release-1"}, new String[]{"board-1", "season-1", "release-2"}))
            failure("CITY_SNAPSHOT_CHANGED", () -> service(ready()).read(41, 2, "region-1", CityPlayerReadService.View.POINTS, ids[0], ids[1], ids[2], 7L));
        failure("CITY_SNAPSHOT_CHANGED", () -> service(ready()).read(41, 2, "region-1", CityPlayerReadService.View.POINTS, "board-1", "season-1", "release-1", 6L));
    }
    @Test void missingExpectedScopeIsRejectedBeforeRead() {
        AtomicInteger calls = new AtomicInteger();
        CityPlayerReadService s = new CityPlayerReadService((a, e, r) -> { calls.incrementAndGet(); return ready(); });
        failure("INVALID_REQUEST", () -> s.read(41, 2, "region-1", CityPlayerReadService.View.POINTS, "board-1", null, "release-1", 7L));
        assertEquals(0, calls.get());
    }
    @Test void foreignParticipationCannotLeakEvenOnBoardRead() {
        for (Participation p : Arrays.asList(new Participation(42, 3, "board-1", "season-1", "p", "release-1", 7),
                new Participation(41, 3, "board-2", "season-1", "p", "release-1", 7), new Participation(41, 3, "board-1", "season-2", "p", "release-1", 7)))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.JOINED, p, Points.AVAILABLE, Collections.emptyList())), CityPlayerReadService.View.BOARD));
    }
    @Test void themeAndUnapprovedLifecycleCannotBecomeCityBoard() {
        Board b = board();
        for (Board invalid : Arrays.asList(new Board("THEME", b.gameId, b.boardId, b.regionId, b.seasonId, b.rulesReleaseId, b.rulesHash, "OPEN", b.title, 7),
                new Board("CITY", b.gameId, b.boardId, b.regionId, b.seasonId, b.rulesReleaseId, b.rulesHash, "DRAFT_UNAPPROVED", b.title, 7)))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(invalid, Membership.NOT_JOINED, null, Points.UNAVAILABLE, Collections.emptyList())), CityPlayerReadService.View.BOARD));
    }
    @Test void pointScopeCoordinateDuplicateAndUnprovenOwnershipFailClosed() {
        for (List<Point> points : Arrays.asList(Arrays.asList(point("same", true), point("same", false)),
                Collections.singletonList(new Point("p", "board-2", "season-1", "P", 0, 0, false, "release-1", 7)),
                Collections.singletonList(new Point("p", "board-1", "season-2", "P", 0, 0, false, "release-1", 7)),
                Collections.singletonList(new Point("p", "board-1", "season-1", "P", Double.NaN, 0, false, "release-1", 7)),
                Collections.singletonList(new Point("p", "board-1", "season-1", "P", 0, 181, false, "release-1", 7))))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.JOINED, participation(), Points.AVAILABLE, points)), CityPlayerReadService.View.POINTS));
        failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.NOT_JOINED, null, Points.AVAILABLE, Collections.singletonList(point("p", true)))), CityPlayerReadService.View.POINTS));
        failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.UNAVAILABLE, null, Points.AVAILABLE, Collections.emptyList())), CityPlayerReadService.View.POINTS));
    }
    @Test void oversizedProjectionDoesNotSilentlyTruncateOrReturnEmpty() {
        List<Point> points = new ArrayList<>(); for (int i = 0; i <= CityPlayerReadService.MAX_POINTS; i++) points.add(point("p" + i, false));
        failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.JOINED, participation(), Points.AVAILABLE, points)), CityPlayerReadService.View.POINTS));
    }
    @Test void nestedRowsCannotMixReleaseOrProjectionRevision() {
        for (Point p : Arrays.asList(new Point("p", "board-1", "season-1", "P", 0, 0, false, "old-release", 7),
                new Point("p", "board-1", "season-1", "P", 0, 0, false, "release-1", 6)))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.JOINED, participation(), Points.AVAILABLE, Collections.singletonList(p))), CityPlayerReadService.View.POINTS));
        for (Participation p : Arrays.asList(new Participation(41, 3, "board-1", "season-1", "p", "old-release", 7),
                new Participation(41, 3, "board-1", "season-1", "p", "release-1", 6)))
            failure("CITY_READ_UNAVAILABLE", () -> read(service(snapshot(board(), Membership.JOINED, p, Points.AVAILABLE, Collections.emptyList())), CityPlayerReadService.View.PARTICIPATION));
    }
    @Test void availableEmptyPointsIsExplicitAndSnapshotDefensivelyCopies() {
        List<Point> points = new ArrayList<>(); Snapshot s = snapshot(board(), Membership.NOT_JOINED, null, Points.AVAILABLE, points);
        points.add(point("p", true));
        assertEquals(Collections.emptyList(), read(service(s), CityPlayerReadService.View.POINTS).get("points"));
        assertThrows(UnsupportedOperationException.class, () -> s.points.add(point("p", false)));
    }
}
