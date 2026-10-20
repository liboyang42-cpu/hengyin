package com.chengyinhub.business.city.draft;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.it.support.IsolatedMySqlSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.chengyinhub.business.city.draft.CityBoardDraftService.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original migration, production JDBC store/service and Oracle MySQL 8.0.45 InnoDB.
 * Authority/catalog values are explicit synthetic fixtures. This does not certify production
 * authorization wiring, process-crash recovery, approval, activation or points settlement.
 * No H2 setup, inherited H2 assertions, Spring application context or business database access. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT", matches="1")
class CityBoardDraftMySqlIT {
    private static final String MIGRATION = "20261003_city_board_draft_candidate";
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    private final IsolatedMySqlSchema mysql = new IsolatedMySqlSchema();
    private DataSource source;
    private JdbcTemplate jdbc;
    private FixtureAuthority authority;
    private CityBoardDraftService service;

    @BeforeEach void openActualMysqlAndApplyOriginalMigration() throws Exception {
        source = mysql.open("boarddraft");
        jdbc = new JdbcTemplate(source);
        mysql.apply(MIGRATION);
        authority = new FixtureAuthority();
        service = service(authority);
    }
    @AfterEach void dropAndReadBackScratch() throws Exception { mysql.close(); }

    @Test void originalMigrationCreatesExactInnoDbBinaryKeysAndRejectsExistingSchema() throws Exception {
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        for (String table : Arrays.asList("city_board_draft", "city_board_draft_receipt")) {
            assertEquals("InnoDB", jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", String.class, table));
            assertEquals("mediumtext", column(table, "snapshot_json", "data_type"));
            assertEquals("NO", column(table, "snapshot_json", "is_nullable"));
        }
        for (String field : Arrays.asList("actor_id", "request_id", "fingerprint"))
            assertEquals("ascii_bin", column("city_board_draft_receipt", field, "collation_name"));
        assertEquals("ascii_bin", column("city_board_draft", "draft_id", "collation_name"));
        assertEquals("bigint", column("city_board_draft", "version", "column_type"));
        assertEquals(Arrays.asList("actor_id", "request_id"), jdbc.queryForList("SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name='city_board_draft_receipt' AND constraint_name='PRIMARY' ORDER BY ordinal_position", String.class));
        SQLException duplicate = assertThrows(SQLException.class, () -> mysql.apply(MIGRATION));
        assertEquals("42S01", duplicate.getSQLState());
        assertRows(0, 0);
    }

    @Test void saveEditAndReopenPreserveUnicodeBytesOnFreshPhysicalConnections() throws Exception {
        Map<String, Object> create = command("create", 0, null);
        map(create.get("payload")).put("name", "城市棋盘 🧭 exact bytes");
        Map<String, Object> first = service.save(create);
        byte[] firstBytes = snapshotBytes();
        assertTrue(new String(firstBytes, StandardCharsets.UTF_8).contains("城市棋盘 🧭 exact bytes"));
        assertEquals(first, JSON.parseObject(firstBytes, Map.class));
        Map<String, Object> edit = command("edit", 1, (String) first.get("payloadHash"));
        map(edit.get("payload")).put("name", "新版本 🌆");
        Map<String, Object> second = service.save(edit);
        try (Connection a = source.getConnection(); Connection b = source.getConnection()) {
            assertNotEquals(connectionId(a), connectionId(b));
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, a.getTransactionIsolation());
        }
        CityBoardDraftService reopened = service(new FixtureAuthority());
        assertEquals(second, reopened.reopen("board"));
        assertEquals(2L, ((Number) second.get("version")).longValue());
        assertEquals(hash(map(second.get("payload"))), second.get("payloadHash"));
        assertRows(1, 2);
    }

    @Test void staleVersionAndWrongHashCannotOverwriteOrPreviewNewerMysqlRow() {
        Map<String, Object> first = create();
        Map<String, Object> edit = command("edit", 1, (String) first.get("payloadHash"));
        map(edit.get("payload")).put("name", "committed newer version");
        Map<String, Object> second = service.save(edit);
        fails(409, "CITY_DRAFT_CONFLICT", () -> service.save(command("stale", 1, (String) first.get("payloadHash"))));
        fails(409, "CITY_DRAFT_CONFLICT", () -> service.save(command("wrong-hash", 2, HASH)));
        fails(409, "CITY_DRAFT_CONFLICT", () -> service.preview(previewCommand(first)));
        assertEquals(second, service.reopen("board"));
        assertRows(1, 2);
    }

    @Test void firstCreateRaceForcesBothAbsentReadsAndCommitsExactlyOneCandidate() throws Exception {
        Map<String, Object> left = command("left", 0, null), right = command("right", 0, null);
        map(right.get("payload")).put("name", "other candidate");
        List<Object> results = race(left, right, true);
        Map<String, Object> winner = onlyWinner(results);
        assertEquals(winner, service.reopen("board"));
        assertRows(1, 1);
        assertEquals(1L, ((Number) winner.get("version")).longValue());
    }

    @Test void identicalConcurrentCreateCanRetryExactCommandAfterUniqueRace() throws Exception {
        Map<String, Object> cmd = command("same", 0, null);
        Map<String, Object> identical = command("same", 0, null);
        assertEquals(cmd, identical, "Race inputs must preserve required explicit null fields");
        Map<String, Object> winner = onlyWinner(race(cmd, identical, true));
        assertEquals(winner, service.save(cmd));
        assertEquals(winner, service(new FixtureAuthority()).save(cmd));
        assertRows(1, 1);
    }

    @Test void sameActorRequestAcrossDifferentDraftsRollsBackLosingEdit() throws Exception {
        Map<String, Object> first = create();
        Map<String, Object> otherCreate = command("other-create", 0, null); otherCreate.put("draftId", "other-board");
        Map<String, Object> other = service.save(otherCreate);
        byte[] firstBytes = snapshotBytes();
        byte[] otherBytes = jdbc.queryForObject("SELECT CAST(snapshot_json AS BINARY) FROM city_board_draft WHERE draft_id='other-board'", byte[].class);
        Map<String, Object> left = command("shared-new-request", 1, (String) first.get("payloadHash"));
        Map<String, Object> right = command("shared-new-request", 1, (String) other.get("payloadHash"));
        right.put("draftId", "other-board");
        map(left.get("payload")).put("name", "receipt race left"); map(right.get("payload")).put("name", "receipt race right");
        // Existing distinct rows avoid the empty-table supremum-gap deadlock: both UPDATEs can
        // succeed independently, so the common receipt primary key must reject the losing INSERT.
        Map<String, Object> winner = onlyWinner(race(left, right, true));
        boolean leftWon = "board".equals(winner.get("draftId"));
        Map<String, Object> losing = leftWon ? right : left;
        String losingId = (String) losing.get("draftId");
        assertEquals(leftWon ? other : first, service.reopen(losingId));
        assertArrayEquals(leftWon ? otherBytes : firstBytes,
                jdbc.queryForObject("SELECT CAST(snapshot_json AS BINARY) FROM city_board_draft WHERE draft_id=?", byte[].class, losingId));
        fails(409, "REQUEST_ID_CONFLICT", () -> service.save(losing));
        assertRows(2, 3);
        assertEquals(2L, ((Number) winner.get("version")).longValue());
        assertEquals(winner, service.reopen((String) winner.get("draftId")));
    }

    @Test void twoConcurrentEditsUsingSameCasCommitOneVersionAndOneNewReceipt() throws Exception {
        Map<String, Object> first = create();
        Map<String, Object> left = command("edit-left", 1, (String) first.get("payloadHash"));
        Map<String, Object> right = command("edit-right", 1, (String) first.get("payloadHash"));
        map(left.get("payload")).put("name", "left CAS"); map(right.get("payload")).put("name", "right CAS");
        Map<String, Object> winner = onlyWinner(race(left, right, false));
        assertEquals(winner, service.reopen("board"));
        assertEquals(2L, ((Number) winner.get("version")).longValue());
        assertRows(1, 2);
    }

    @Test void mysqlReceiptTriggerFailureRollsBackCreateAndExactRetrySucceeds() {
        rejectReceipt();
        Map<String, Object> cmd = command("reject", 0, null);
        fails(503, "CITY_DRAFT_STORAGE_UNAVAILABLE", () -> service.save(cmd));
        assertRows(0, 0);
        jdbc.execute("DROP TRIGGER reject_test_receipt");
        Map<String, Object> saved = service.save(cmd);
        assertEquals(saved, service.save(cmd));
        assertRows(1, 1);
    }

    @Test void mysqlReceiptTriggerFailureRollsBackEditBytesAndVersion() {
        Map<String, Object> first = create(); byte[] before = snapshotBytes(); rejectReceipt();
        Map<String, Object> edit = command("reject", 1, (String) first.get("payloadHash"));
        map(edit.get("payload")).put("name", "must roll back");
        fails(503, "CITY_DRAFT_STORAGE_UNAVAILABLE", () -> service.save(edit));
        assertArrayEquals(before, snapshotBytes()); assertEquals(first, service.reopen("board")); assertRows(1, 1);
        jdbc.execute("DROP TRIGGER reject_test_receipt");
        assertEquals(2L, ((Number) service.save(edit).get("version")).longValue()); assertRows(1, 2);
    }

    @Test void exactReceiptRemainsHistoricalAfterEditsAndCatalogRevocation() {
        Map<String, Object> cmd = command("create", 0, null), first = service.save(cmd);
        Map<String, Object> edit = command("edit", 1, (String) first.get("payloadHash"));
        map(edit.get("payload")).put("name", "newer"); Map<String, Object> second = service.save(edit);
        map(((List<?>) authority.facts.get("references")).get(0)).put("approved", false);
        assertEquals(first, service.save(cmd));
        fails(400, "REFERENCE_INVALID", () -> service.save(command("new", 2, (String) second.get("payloadHash"))));
        Map<String, Object> changed = command("create", 0, null);
        assertEquals(cmd, changed, "Conflict fixture must be a valid exact command before its payload changes");
        map(changed.get("payload")).put("name", "different body");
        fails(409, "REQUEST_ID_CONFLICT", () -> service.save(changed));
        assertEquals(second, service.reopen("board")); assertRows(1, 2);
    }

    @Test void binaryDraftAndRequestKeysAndActorReceiptScopeAreExact() {
        Map<String, Object> first = create();
        fails(404, "CITY_DRAFT_NOT_FOUND", () -> service.reopen("Board"));
        Map<String, Object> upper = command("CREATE", 0, null); upper.put("draftId", "Board"); service.save(upper);
        authority.context = new CityBoardDraftAuthority.Context("Editor", Collections.singleton("region"), true);
        fails(409, "CITY_DRAFT_CONFLICT", () -> service.save(command("create", 0, null)));
        Map<String, Object> otherActor = command("create", 0, null); otherActor.put("draftId", "third-board"); service.save(otherActor);
        assertEquals("editor", first.get("updatedBy"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT actor_id) FROM city_board_draft_receipt", Integer.class));
        assertRows(3, 3);
    }

    @Test void revokedRegionNonPlatformAndRegionMoveNeverMutateOrReplay() {
        Map<String, Object> first = create(); byte[] originalBytes = snapshotBytes();
        authority.context = new CityBoardDraftAuthority.Context("editor", Collections.singleton("other-region"), true);
        fails(403, "REGION_FORBIDDEN", () -> service.save(command("create", 0, null)));
        fails(403, "REGION_FORBIDDEN", () -> service.reopen("board"));
        fails(403, "REGION_FORBIDDEN", () -> service.preview(previewCommand(first)));
        Map<String, Object> concealCurrentRegion = command("edit", 1, (String) first.get("payloadHash"));
        map(concealCurrentRegion.get("payload")).put("regionId", "other-region");
        fails(403, "REGION_FORBIDDEN", () -> service.save(concealCurrentRegion));
        authority.context = new CityBoardDraftAuthority.Context("editor", new HashSet<>(Arrays.asList("region", "other-region")), true);
        fails(400, "REGION_IMMUTABLE", () -> service.save(concealCurrentRegion));
        authority.context = new CityBoardDraftAuthority.Context("merchant", Collections.singleton("region"), false);
        fails(403, "PLATFORM_EDITOR_REQUIRED", () -> service.save(command("create", 0, null)));
        fails(403, "PLATFORM_EDITOR_REQUIRED", () -> service.reopen("board"));
        fails(403, "PLATFORM_EDITOR_REQUIRED", () -> service.preview(previewCommand(first)));
        assertArrayEquals(originalBytes, snapshotBytes());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM city_board_draft", Long.class));
        assertRows(1, 1);
    }

    @Test void signedIntegerPointsMaxRoundTripsAndOverflowCannotWrite() {
        Map<String, Object> cmd = command("max", 0, null);
        Map<String, Object> rule = map(map(cmd.get("payload")).get("rule"));
        Map<String, Object> limits = map(map(((List<?>) authority.facts.get("references")).get(0)).get("limits"));
        List<String> pointFields = Arrays.asList("acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints");
        for (String field : pointFields) { rule.put(field, Integer.MAX_VALUE); limits.put(field, object("min", 0, "max", Integer.MAX_VALUE)); }
        Map<String, Object> saved = service.save(cmd);
        for (String field : pointFields)
            assertEquals((long) Integer.MAX_VALUE, ((Number) map(map(service.reopen("board").get("payload")).get("rule")).get(field)).longValue());
        for (String field : pointFields) for (Object invalid : Arrays.asList(-1L, (long) Integer.MAX_VALUE + 1, 1.5, "1")) {
            Map<String, Object> bad = command("invalid", 1, (String) saved.get("payloadHash"));
            map(map(bad.get("payload")).get("rule")).put(field, invalid);
            fails(400, invalid instanceof Long ? "INTEGER_OUT_OF_RANGE" : "INTEGER_REQUIRED", () -> service.save(bad));
        }
        assertEquals(saved, service.reopen("board")); assertRows(1, 1);
    }

    @Test void signedVersionBoundaryCannotWrapAfterLargestAcceptedSave() {
        Map<String, Object> seeded = create();
        seeded.put("version", Integer.MAX_VALUE - 1);
        jdbc.update("UPDATE city_board_draft SET version=?,snapshot_json=? WHERE draft_id='board'", Integer.MAX_VALUE - 1, JSON.toJSONString(seeded));
        Map<String, Object> atLimit = service.save(command("last", Integer.MAX_VALUE - 1L, (String) seeded.get("payloadHash")));
        assertEquals((long) Integer.MAX_VALUE, ((Number) atLimit.get("version")).longValue());
        assertEquals((long) Integer.MAX_VALUE, jdbc.queryForObject("SELECT version FROM city_board_draft", Long.class));
        fails(400, "INTEGER_OUT_OF_RANGE", () -> service.save(command("overflow", Integer.MAX_VALUE, (String) atLimit.get("payloadHash"))));
        assertEquals(atLimit, service.reopen("board")); assertRows(1, 2);
    }

    @Test void defaultOffAndReadOnlyPreviewCannotActivateOrTouchPointSentinels() {
        jdbc.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY, point INT NOT NULL) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE ums_member_points_detail(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO ums_member(id,point) VALUES(1,2147483647)");
        fails(503, "CITY_DRAFT_DISABLED", () -> new CityBoardDraftService().save(command("off", 0, null)));
        assertRows(0, 0);
        assertEquals(true, service.validate(command("validate", 0, null)).get("draftOnly")); assertRows(0, 0);
        Map<String, Object> saved = create(); byte[] before = snapshotBytes();
        Map<String, Object> preview = service.preview(previewCommand(saved));
        assertEquals("DRAFT_UNAPPROVED", saved.get("status")); assertEquals("DRAFT_UNAPPROVED", preview.get("status"));
        assertEquals(true, preview.get("readOnly")); assertEquals(Collections.emptyList(), preview.get("player_strategy"));
        assertArrayEquals(before, snapshotBytes()); assertRows(1, 1);
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT point FROM ums_member WHERE id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class));
        assertEquals(new HashSet<>(Arrays.asList("city_board_draft", "city_board_draft_receipt", "ums_member", "ums_member_points_detail")),
                new HashSet<>(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()", String.class)));
    }

    @Test void corruptedMysqlPayloadCannotBeReopenedOrPreviewed() {
        Map<String, Object> saved = create();
        jdbc.update("UPDATE city_board_draft SET snapshot_json=REPLACE(snapshot_json,?,?)", "fixture board", "tampered board");
        fails(500, "CORRUPT_DRAFT", () -> service.reopen("board"));
        fails(500, "CORRUPT_DRAFT", () -> service.preview(previewCommand(saved)));
        assertRows(1, 1);
    }

    private CityBoardDraftService service(CityBoardDraftAuthority a) {
        return new CityBoardDraftService(CityBoardDraftService.Mode.ISOLATED_DRAFT_EVALUATION, new JdbcCityBoardDraftStore(source), a);
    }
    private Map<String, Object> create() { return service.save(command("create", 0, null)); }
    private void assertRows(int drafts, int receipts) {
        assertEquals(drafts, jdbc.queryForObject("SELECT COUNT(*) FROM city_board_draft", Integer.class));
        assertEquals(receipts, jdbc.queryForObject("SELECT COUNT(*) FROM city_board_draft_receipt", Integer.class));
    }
    private String column(String table, String field, String property) {
        return jdbc.queryForObject("SELECT " + property + " FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, field);
    }
    private byte[] snapshotBytes() { return jdbc.queryForObject("SELECT CAST(snapshot_json AS BINARY) FROM city_board_draft WHERE draft_id='board'", byte[].class); }
    private void rejectReceipt() {
        jdbc.execute("CREATE TRIGGER reject_test_receipt BEFORE INSERT ON city_board_draft_receipt FOR EACH ROW BEGIN IF NEW.request_id='reject' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated receipt failure'; END IF; END");
    }
    private static void fails(int status, String code, Runnable call) {
        Failure failure = assertThrows(Failure.class, call::run); assertEquals(status, failure.status); assertEquals(code, failure.getMessage());
    }
    private static long connectionId(Connection c) throws SQLException {
        try (Statement sql = c.createStatement(); ResultSet row = sql.executeQuery("SELECT CONNECTION_ID()")) { assertTrue(row.next()); return row.getLong(1); }
    }
    private List<Object> race(Map<String, Object> left, Map<String, Object> right, boolean synchronizeValidation) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2); AtomicInteger arrivals = new AtomicInteger();
        FixtureAuthority a = new FixtureAuthority() {
            @Override public Context requireCurrentPlatformEditor() { if (!synchronizeValidation) meet(); return super.requireCurrentPlatformEditor(); }
            @Override public Map<String, Object> catalog(Context c) { if (synchronizeValidation) meet(); return super.catalog(c); }
            private void meet() {
                arrivals.incrementAndGet();
                try { barrier.await(8, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError("Both independent transactions must reach the barrier", e); }
            }
        };
        CityBoardDraftService concurrent = service(a); ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> one = pool.submit(() -> attempt(concurrent, left));
            Future<Object> two = pool.submit(() -> attempt(concurrent, right));
            List<Object> results = Arrays.asList(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
            assertEquals(2, arrivals.get(), synchronizeValidation ? "Both transactions pass current-row/receipt/CAS reads before validation and write" : "Both contenders enter with the same stale CAS");
            return results;
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "No racing writer may outlive its scratch schema"); }
    }
    private static Object attempt(CityBoardDraftService service, Map<String, Object> cmd) {
        try { return service.save(cmd); } catch (Failure failure) { return failure; }
    }
    private static Map<String, Object> onlyWinner(List<Object> outcomes) {
        List<Map<String, Object>> winners = new ArrayList<>(); int conflicts = 0;
        for (Object value : outcomes) {
            if (value instanceof Map) winners.add(map(value));
            else { assertTrue(value instanceof Failure); Failure failure = (Failure) value; assertEquals(409, failure.status); assertEquals("CITY_DRAFT_CONFLICT", failure.getMessage()); conflicts++; }
        }
        assertEquals(1, winners.size()); assertEquals(1, conflicts); return winners.get(0);
    }
    private static Map<String, Object> previewCommand(Map<String, Object> saved) {
        return object("draftId", saved.get("draftId"), "expectedVersion", saved.get("version"), "expectedHash", saved.get("payloadHash"));
    }
    private static Map<String, Object> command(String request, long version, String hash) {
        return object("draftId", "board", "requestId", request, "expectedVersion", version, "expectedHash", hash,
                "payload", object("classification", "platform_rule", "name", "fixture board", "regionId", "region", "timezone", "UTC",
                    "opensAt", "2026-10-05T00:00:00Z", "closesAt", "2026-10-06T00:00:00Z",
                    "rule", object("rulesRef", ref("rules"), "taskRef", ref("task"), "diceRef", ref("dice"), "challengeRef", ref("challenge"), "settlementRef", ref("settlement"),
                        "actionBudget", 2, "maxHoldings", 1, "protectionSeconds", 0, "acquisitionCostPoints", 0, "serviceFeePoints", 0, "taskRewardPoints", 1, "rewardBudgetPoints", 10),
                    "servicePoints", Collections.singletonList(object("placeId", "place", "role", "SERVICE"))));
    }
    private static Map<String, Object> ref(String id) { return object("id", id, "version", 1, "hash", HASH); }
    private static class FixtureAuthority implements CityBoardDraftAuthority {
        Context context = new Context("editor", Collections.singleton("region"), true);
        final Map<String, Object> facts = facts();
        @Override public Context requireCurrentPlatformEditor() { return context; }
        @Override public Map<String, Object> catalog(Context ignored) { return copy(facts); }
        private static Map<String, Object> facts() {
            List<Object> refs = new ArrayList<>();
            for (String kind : Arrays.asList("RULES", "TASK", "DICE", "CHALLENGE", "SETTLEMENT")) {
                Map<String, Object> item = ref(kind.toLowerCase(Locale.ROOT));
                item.putAll(object("regionId", "region", "scope", "CITY", "kind", kind, "approved", true, "feasible", true, "name", "fixture " + kind));
                if ("RULES".equals(kind)) {
                    Map<String, Object> limits = new HashMap<>();
                    for (String field : Arrays.asList("actionBudget", "maxHoldings", "protectionSeconds", "acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints"))
                        limits.put(field, object("min", 0, "max", 100));
                    item.put("limits", limits);
                }
                refs.add(item);
            }
            return object("references", refs, "places", Collections.singletonList(object("placeId", "place", "regionId", "region", "name", "fixture place", "version", 1, "hash", HASH,
                    "authorized", true, "licensed", true, "public", true, "safe", true, "open", true, "capacity", 1, "taskIds", Collections.singletonList("task"))));
        }
    }
}
