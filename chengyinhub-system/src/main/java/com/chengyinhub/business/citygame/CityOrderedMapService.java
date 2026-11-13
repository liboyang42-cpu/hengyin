package com.chengyinhub.business.citygame;

import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Ordered map (CITY_TRANSACTIONAL_MAP_V1): /state/snapshot?sync=TRANSACTIONAL_EVENTS_V1 opens a stream for one
 * exact viewport query and returns the bounded snapshot with per-point component versions; /state/deltas?cursor=
 * replays committed point changes after it, in order and without gaps. Every change is a full POINT_REPLACED of
 * one component: SLOT (ownership, from the board engine), MERCHANT or PLATFORM (operations tooling). Stream
 * sequences count only the events inside the stream's viewport. A stream lives as long as its snapshot permit;
 * a stale or foreign stream answers 409 CITY_ORDERED_RESET_REQUIRED and the app starts again from a snapshot.
 */
@Service
public class CityOrderedMapService {
    static final String SYNC = "TRANSACTIONAL_EVENTS_V1";

    private final CityR491Store store;
    private final CityGameReadService reads;
    private final TransactionTemplate consistent;

    public CityOrderedMapService(CityR491Store store, CityGameReadService reads, org.springframework.transaction.PlatformTransactionManager transactions) {
        this.store = store;
        this.reads = reads;
        this.consistent = new TransactionTemplate(transactions);
        this.consistent.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    static String scopeHash(Map<String, String> q) {
        Map<String, String> scope = new TreeMap<>(q);
        scope.remove("cursor");
        return CityGameRules.sha256(com.chengyinhub.business.appgaps.AppGapSupport.canonical(scope));
    }

    private Map<String, Map<String, Long>> versions(String boardId, List<Map<String, Object>> points) {
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            Map<String, Object> row = store.jdbc.queryForMap("SELECT slot_version, merchant_version, platform_version FROM city_game_point WHERE board_id = ? AND point_id = ?",
                    boardId, point.get("pointId"));
            Map<String, Long> v = new LinkedHashMap<>();
            // SLOT is versioned exactly where the snapshot evaluated ownership.
            if (!"NOT_EVALUATED".equals(point.get("ownership"))) v.put("SLOT", ((Number) row.get("slot_version")).longValue());
            v.put("MERCHANT", ((Number) row.get("merchant_version")).longValue());
            v.put("PLATFORM", ((Number) row.get("platform_version")).longValue());
            out.put((String) point.get("pointId"), v);
        }
        return out;
    }

    private Map<String, Object> header(String kind, String streamId, String scopeHash, long sequence, long revision, long captured, long validUntil) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_TRANSACTIONAL_MAP_V1");
        out.put("scope", "CITY");
        out.put("mode", SYNC);
        out.put("kind", kind);
        out.put("streamId", streamId);
        out.put("scopeHash", scopeHash);
        out.put("cursor", streamId + "." + sequence);
        out.put("sequence", sequence);
        out.put("boardVersion", revision);
        out.put("capturedAtMillis", captured);
        out.put("validUntilMillis", validUntil);
        out.put("grantsAction", false);
        out.put("grantsNavigation", false);
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(Long memberId, Map<String, String> q) {
        CityGameSupport.requireMember(memberId);
        if (!SYNC.equals(q.get("sync")) || q.containsKey("cursor")) throw new CityGameException(400, "sync", "sync invalid");
        Map<String, String> query = new LinkedHashMap<>(q);
        query.remove("sync");
        return consistent.execute(status -> {
            String regionId = CityGameSupport.requireId(query.get("regionId"), "regionId");
            Map<String, Object> board = store.board(regionId);
            String boardId = (String) board.get("boardId");
            Long base = store.jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM city_point_event WHERE board_id = ?", Long.class, boardId);
            Map<String, Object> snap = reads.stateSnapshot(memberId, query);
            Membership m = store.membership(board, memberId);
            List<Map<String, Object>> points = (List<Map<String, Object>>) snap.get("points");
            long captured = ((Number) snap.get("capturedAtMillis")).longValue();
            long validUntil = ((Number) snap.get("validUntilMillis")).longValue();
            String streamId = UUID.randomUUID().toString();
            String scope = scopeHash(query);
            long revision = ((Number) board.get("revision")).longValue();
            store.jdbc.update("INSERT INTO city_map_stream(stream_id, member_id, board_id, scope_hash, team_id, base_event_id, revision, valid_until) VALUES (?,?,?,?,?,?,?,?)",
                    streamId, memberId, boardId, scope, m.joined() ? m.teamId() : null, base, revision, new Date(validUntil));
            Map<String, Object> out = header("SNAPSHOT", streamId, scope, 0, revision, captured, validUntil);
            out.put("snapshot", snap);
            out.put("versions", versions(boardId, points));
            return out;
        });
    }

    public Map<String, Object> deltas(Long memberId, Map<String, String> q) {
        CityGameSupport.requireMember(memberId);
        String cursor = q.get("cursor");
        if (!SYNC.equals(q.get("sync")) || cursor == null || !cursor.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(0|[1-9][0-9]{0,15})")) {
            throw new CityGameException(400, "cursor", "cursor invalid");
        }
        String streamId = cursor.substring(0, 36);
        long after = Long.parseLong(cursor.substring(37));
        Map<String, String> query = new LinkedHashMap<>(q);
        query.remove("sync");
        List<Map<String, Object>> streams = store.jdbc.queryForList("SELECT * FROM city_map_stream WHERE stream_id = ? AND member_id = ?", streamId, memberId);
        if (streams.isEmpty()) throw reset();
        Map<String, Object> stream = streams.get(0);
        long now = store.now();
        long validUntil = CityGameSupport.millis(stream.get("valid_until"));
        if (validUntil <= now || !scopeHash(query).equals(stream.get("scope_hash"))) throw reset();
        String regionId = CityGameSupport.requireId(query.get("regionId"), "regionId");
        Map<String, Object> board = store.board(regionId);
        String boardId = (String) board.get("boardId");
        if (!boardId.equals(stream.get("board_id")) || ((Number) board.get("revision")).longValue() != ((Number) stream.get("revision")).longValue()
                || !String.valueOf(stream.get("revision")).equals(query.get("revision"))) {
            throw reset();
        }
        Membership m = store.membership(board, memberId);
        String team = m.joined() ? m.teamId() : null;
        if (!java.util.Objects.equals(team, stream.get("team_id"))) throw reset();
        BigDecimal south = new BigDecimal(query.get("south")), west = new BigDecimal(query.get("west"));
        BigDecimal north = new BigDecimal(query.get("north")), east = new BigDecimal(query.get("east"));
        List<String> layers = java.util.Arrays.asList(query.get("layers").split(","));
        Map<String, Map<String, Object>> places = new LinkedHashMap<>();
        for (Map<String, Object> row : store.jdbc.queryForList("SELECT * FROM city_game_point WHERE board_id = ? AND del_flag = 0", boardId)) {
            BigDecimal lat = (BigDecimal) row.get("latitude"), lng = (BigDecimal) row.get("longitude");
            if (!layers.contains((String) row.get("domain")) || lat.compareTo(south) < 0 || lat.compareTo(north) > 0
                    || lng.compareTo(west) < 0 || lng.compareTo(east) > 0) continue;
            places.put((String) row.get("point_id"), row);
        }
        java.util.Set<String> owned = new java.util.HashSet<>(store.jdbc.queryForList(
                "SELECT DISTINCT point_id FROM city_game_property WHERE board_id = ?", String.class, boardId));
        List<Map<String, Object>> events = new ArrayList<>();
        long sequence = 0;
        for (Map<String, Object> e : store.jdbc.queryForList("SELECT * FROM city_point_event WHERE board_id = ? AND id > ? ORDER BY id",
                boardId, ((Number) stream.get("base_event_id")).longValue())) {
            Map<String, Object> place = places.get(e.get("point_id"));
            if (place == null) continue;
            sequence++;
            if (sequence <= after) continue;
            if (events.size() == 500) break;
            events.add(event(streamId, sequence, e, place, team, owned.contains(e.get("point_id"))));
        }
        if (after > sequence) throw reset();
        long last = events.isEmpty() ? after : ((Number) events.get(events.size() - 1).get("sequence")).longValue();
        Map<String, Object> out = header("DELTAS", streamId, (String) stream.get("scope_hash"), last, ((Number) board.get("revision")).longValue(), now, validUntil);
        out.put("afterSequence", after);
        List<Map<String, Object>> batches = new ArrayList<>();
        if (!events.isEmpty()) {
            Map<String, Object> batch = new LinkedHashMap<>();
            batch.put("batchId", CityGameRules.sha256(streamId + ":" + (after + 1) + ":" + last));
            batch.put("firstSequence", after + 1);
            batch.put("lastSequence", last);
            batch.put("events", events);
            batches.add(batch);
        }
        out.put("batches", batches);
        return out;
    }

    private Map<String, Object> event(String streamId, long sequence, Map<String, Object> e, Map<String, Object> place, String team, boolean evaluated) {
        String holder = (String) e.get("holder_team_id");
        String ownership = !evaluated ? "NOT_EVALUATED" : holder == null ? "UNOWNED" : holder.equals(team) ? "OUR_TEAM" : "OTHER_TEAM";
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("pointId", place.get("point_id"));
        point.put("title", place.get("title"));
        point.put("latitude", ((BigDecimal) place.get("latitude")).stripTrailingZeros());
        point.put("longitude", ((BigDecimal) place.get("longitude")).stripTrailingZeros());
        point.put("domain", place.get("domain"));
        point.put("datum", place.get("map_datum") == null ? store.jdbc.queryForObject("SELECT map_datum FROM city_game_board WHERE board_id = ?", String.class, place.get("board_id")) : place.get("map_datum"));
        point.put("collectedAt", CityGameSupport.iso(place.get("collected_at")));
        point.put("coordinateAssurance", "PLATFORM_REVIEWED_METADATA");
        point.put("availability", e.get("availability"));
        point.put("merchantVersion", ((Number) e.get("merchant_version")).longValue());
        point.put("platformVersion", ((Number) e.get("platform_version")).longValue());
        point.put("mine", "OUR_TEAM".equals(ownership));
        point.put("ownership", ownership);
        point.put("grantsNavigation", false);
        point.put("grantsAction", false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", CityGameRules.sha256(streamId + ":" + sequence));
        out.put("aggregate", place.get("point_id"));
        out.put("component", e.get("component"));
        out.put("type", "POINT_REPLACED");
        out.put("sequence", sequence);
        out.put("aggregateVersion", ((Number) e.get("aggregate_version")).longValue());
        out.put("point", point);
        return out;
    }

    private static CityGameException reset() { return new CityGameException(409, "CITY_ORDERED_RESET_REQUIRED", "地图需要重新读取"); }
}
