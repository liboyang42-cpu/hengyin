package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Map;
import java.util.function.Supplier;
import static com.chengyinhub.business.city.draft.CityBoardDraftService.*;

/** Isolated SQL candidate, no auto-registration. One transaction commits draft + exact replay receipt. */
public final class JdbcCityBoardDraftStore {
    private final DataSource source;
    public JdbcCityBoardDraftStore(DataSource source) { this.source = java.util.Objects.requireNonNull(source); }
    public Map<String, Object> save(CityBoardDraftAuthority.Context context, Map<String, Object> command,
                                    String fingerprint, Supplier<Map<String, Object>> validation) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                String draftId = (String) command.get("draftId");
                Map<String, Object> current = select(c, draftId, true);
                if (current != null) require(context.regionIds.contains(map(current.get("payload")).get("regionId")), 403, "REGION_FORBIDDEN");
                try (PreparedStatement q = c.prepareStatement("SELECT fingerprint,snapshot_json FROM city_board_draft_receipt WHERE actor_id=? AND request_id=?")) {
                    q.setString(1, context.actorId); q.setString(2, (String) command.get("requestId"));
                    try (ResultSet r = q.executeQuery()) {
                        if (r.next()) {
                            require(fingerprint.equals(r.getString(1)), 409, "REQUEST_ID_CONFLICT");
                            Map<String, Object> replay = decode(r.getString(2)); verifySnapshot(replay); c.commit(); return replay;
                        }
                    }
                }
                long expected = ((Number) command.get("expectedVersion")).longValue();
                require(current == null ? expected == 0 : expected == ((Number) current.get("version")).longValue()
                        && command.get("expectedHash").equals(current.get("payloadHash")), 409, "CITY_DRAFT_CONFLICT");
                Map<String, Object> payload = map(command.get("payload"));
                if (current != null) require(map(current.get("payload")).get("regionId").equals(payload.get("regionId")), 400, "REGION_IMMUTABLE");
                Map<String, Object> bindings = validation.get();
                Map<String, Object> snapshot = object("draftId", draftId, "version", expected + 1, "payloadHash", hash(payload),
                        "status", "DRAFT_UNAPPROVED", "payload", payload, "bindings", bindings, "updatedBy", context.actorId);
                String json = JSON.toJSONString(snapshot);
                String sql = current == null ? "INSERT INTO city_board_draft(draft_id,version,snapshot_json) VALUES(?,?,?)"
                        : "UPDATE city_board_draft SET version=?,snapshot_json=? WHERE draft_id=? AND version=?";
                try (PreparedStatement write = c.prepareStatement(sql)) {
                    if (current == null) { write.setString(1, draftId); write.setLong(2, expected + 1); write.setString(3, json); }
                    else { write.setLong(1, expected + 1); write.setString(2, json); write.setString(3, draftId); write.setLong(4, expected); }
                    require(write.executeUpdate() == 1, 409, "CITY_DRAFT_CONFLICT");
                }
                try (PreparedStatement write = c.prepareStatement("INSERT INTO city_board_draft_receipt(actor_id,request_id,fingerprint,snapshot_json) VALUES(?,?,?,?)")) {
                    write.setString(1, context.actorId); write.setString(2, (String) command.get("requestId"));
                    write.setString(3, fingerprint); write.setString(4, json); write.executeUpdate();
                }
                c.commit(); return copy(snapshot);
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                if (e instanceof SQLException) {
                    String state = ((SQLException) e).getSQLState();
                    if (state != null && (state.startsWith("23") || state.startsWith("40"))) throw new Failure(409, "CITY_DRAFT_CONFLICT");
                }
                throw e;
            }
        } catch (SQLException e) { throw new Failure(503, "CITY_DRAFT_STORAGE_UNAVAILABLE"); }
    }
    public Map<String, Object> read(String draftId) {
        try (Connection c = source.getConnection()) {
            Map<String, Object> value = select(c, draftId, false);
            require(value != null, 404, "CITY_DRAFT_NOT_FOUND"); return value;
        } catch (SQLException e) { throw new Failure(503, "CITY_DRAFT_STORAGE_UNAVAILABLE"); }
    }
    private Map<String, Object> select(Connection c, String id, boolean lock) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT version,snapshot_json FROM city_board_draft WHERE draft_id=?" + (lock ? " FOR UPDATE" : ""))) {
            q.setString(1, id);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return null;
                Map<String, Object> value = decode(r.getString(2)); verifySnapshot(value);
                require(id.equals(value.get("draftId")) && r.getLong(1) == ((Number) value.get("version")).longValue(), 500, "CORRUPT_DRAFT");
                return value;
            }
        }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> decode(String value) {
        try { return JSON.parseObject(value, Map.class); }
        catch (RuntimeException e) { throw new Failure(500, "CORRUPT_DRAFT"); }
    }
}
