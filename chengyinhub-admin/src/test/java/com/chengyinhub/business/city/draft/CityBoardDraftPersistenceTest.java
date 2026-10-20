package com.chengyinhub.business.city.draft;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.city.draft.CityBoardDraftService.*;

/** Synthetic approved test authority only. This is not an implementation of production RBAC. */
public class CityBoardDraftPersistenceTest {
    @TempDir Path directory;
    JdbcDataSource source;
    TestAuthority authority;
    CityBoardDraftService service;
    @BeforeEach void setup() throws Exception {
        source = database(directory.resolve("drafts").toString()); initialize(source);
        authority = new TestAuthority(); service = service(source, authority);
    }
    public static JdbcDataSource database(String path) {
        JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:file:" + path + ";MODE=MySQL;LOCK_TIMEOUT=5000"); return source;
    }
    public static void initialize(JdbcDataSource source) throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            // H2-specific isolated test DDL. It does not validate or apply the MySQL proposal.
            s.execute("CREATE TABLE city_board_draft(draft_id VARCHAR(64) PRIMARY KEY,version BIGINT NOT NULL,snapshot_json CLOB NOT NULL)");
            s.execute("CREATE TABLE city_board_draft_receipt(actor_id VARCHAR(64),request_id VARCHAR(64),fingerprint VARCHAR(64) NOT NULL,snapshot_json CLOB NOT NULL,PRIMARY KEY(actor_id,request_id))");
        }
    }
    public static CityBoardDraftService service(JdbcDataSource source, TestAuthority authority) {
        return new CityBoardDraftService(CityBoardDraftService.Mode.ISOLATED_DRAFT_EVALUATION, new JdbcCityBoardDraftStore(source), authority);
    }
    public static class TestAuthority implements CityBoardDraftAuthority {
        public volatile Context context = new Context("test-platform", new HashSet<>(Arrays.asList("test-region")), true);
        public final Map<String, Object> facts = facts();
        @Override public Context requireCurrentPlatformEditor() { return context; }
        @Override public Map<String, Object> catalog(Context ignored) { return copy(facts); }
    }
    static final String HASH = String.join("", Collections.nCopies(64, "a"));
    public static Map<String, Object> ref(String id) { return object("id", id, "version", 1, "hash", HASH); }
    public static Map<String, Object> payload() {
        return object("classification", "platform_rule", "name", "隔离测试棋盘", "regionId", "test-region", "timezone", "Asia/Shanghai",
            "opensAt", "2026-10-05T00:00:00Z", "closesAt", "2026-10-06T00:00:00Z",
            "rule", object("rulesRef", ref("test-rules"), "taskRef", ref("test-task"), "diceRef", ref("test-dice"),
                "challengeRef", ref("test-challenge"), "settlementRef", ref("test-settlement"), "actionBudget", 2,
                "maxHoldings", 1, "protectionSeconds", 0, "acquisitionCostPoints", 0, "serviceFeePoints", 0,
                "taskRewardPoints", 1, "rewardBudgetPoints", 10),
            "servicePoints", Arrays.asList(object("placeId", "test-place", "role", "SERVICE")));
    }
    public static Map<String, Object> facts() {
        List<Object> refs = new ArrayList<>();
        for (String kind : Arrays.asList("RULES", "TASK", "DICE", "CHALLENGE", "SETTLEMENT")) {
            Map<String, Object> item = ref("test-" + kind.toLowerCase(Locale.ROOT));
            item.putAll(object("regionId", "test-region", "scope", "CITY", "kind", kind, "approved", true, "feasible", true, "name", "测试 " + kind));
            if ("RULES".equals(kind)) {
                Map<String, Object> limits = new HashMap<>();
                for (String field : Arrays.asList("actionBudget", "maxHoldings", "protectionSeconds", "acquisitionCostPoints", "serviceFeePoints", "taskRewardPoints", "rewardBudgetPoints"))
                    limits.put(field, object("min", 0, "max", 100));
                item.put("limits", limits);
            }
            refs.add(item);
        }
        Map<String, Object> place = object("placeId", "test-place", "regionId", "test-region", "name", "测试服务点", "version", 1,
            "hash", HASH, "authorized", true, "licensed", true, "public", true, "safe", true, "open", true, "capacity", 1, "taskIds", Arrays.asList("test-task"));
        return object("references", refs, "places", Arrays.asList(place));
    }
    public static Map<String, Object> command(String request, long version, String hash) {
        return object("draftId", "test-board", "requestId", request, "expectedVersion", version, "expectedHash", hash, "payload", payload());
    }
    Map<String, Object> create() { return service.save(command("create", 0, null)); }
    Map<String, Object> previewCommand(Map<String, Object> saved) { return object("draftId", saved.get("draftId"), "expectedVersion", saved.get("version"), "expectedHash", saved.get("payloadHash")); }
    void fails(String code, Runnable action) { assertEquals(code, assertThrows(Failure.class, action::run).getMessage()); }
    int count(String table) throws SQLException { try (Connection c = source.getConnection(); ResultSet r = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); } }
    @Test void createEditValidateSaveReopenPreviewIsDurable() throws Exception {
        assertEquals(true, service.validate(command("validate", 0, null)).get("valid")); assertEquals(0, count("city_board_draft"));
        Map<String, Object> first = create(), edit = command("edit", 1, (String) first.get("payloadHash"));
        map(edit.get("payload")).put("name", "Edited board");
        Map<String, Object> second = service.save(edit); assertEquals(2L, ((Number) second.get("version")).longValue());
        CityBoardDraftService reopened = service(database(directory.resolve("drafts").toString()), new TestAuthority());
        assertEquals("Edited board", map(reopened.reopen("test-board").get("payload")).get("name"));
        Map<String, Object> preview = reopened.preview(previewCommand(second));
        assertEquals(true, preview.get("readOnly")); assertEquals("DRAFT_UNAPPROVED", preview.get("status"));
        assertEquals(1, count("city_board_draft")); assertEquals(2, count("city_board_draft_receipt"));
    }
    @Test void exactRequestReplayReturnsOriginalSnapshotAfterLaterEdits() {
        Map<String, Object> first = create(); service.save(command("edit", 1, (String) first.get("payloadHash")));
        assertEquals(first, service.save(command("create", 0, null)));
        Map<String, Object> collision = command("create", 0, null); map(collision.get("payload")).put("name", "Other");
        fails("REQUEST_ID_CONFLICT", () -> service.save(collision));
    }
    @Test void sameRequestAcrossBoardsCannotCreateAnotherDraft() throws Exception {
        create(); Map<String, Object> other = command("create", 0, null); other.put("draftId", "other-board");
        fails("REQUEST_ID_CONFLICT", () -> service.save(other)); assertEquals(1, count("city_board_draft"));
    }
    @Test void staleVersionOrHashNeverOverwrites() {
        Map<String, Object> first = create(); service.save(command("edit", 1, (String) first.get("payloadHash")));
        fails("CITY_DRAFT_CONFLICT", () -> service.save(command("stale", 1, (String) first.get("payloadHash"))));
        fails("CITY_DRAFT_CONFLICT", () -> service.save(command("bad-hash", 2, HASH)));
        fails("CITY_DRAFT_CONFLICT", () -> service.preview(previewCommand(first)));
    }
    @Test void concurrentCasHasOneWinner() throws Exception {
        Map<String, Object> first = create(); ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) { final String request = "r" + i; results.add(pool.submit(() -> {
                start.await(); try { service.save(command(request, 1, (String) first.get("payloadHash"))); return "ok"; } catch (Failure e) { return e.getMessage(); }
            })); }
            start.countDown(); List<String> values = Arrays.asList(results.get(0).get(), results.get(1).get());
            assertEquals(1, Collections.frequency(values, "ok")); assertEquals(1, Collections.frequency(values, "CITY_DRAFT_CONFLICT"));
            assertEquals(2, count("city_board_draft_receipt"));
        } finally { pool.shutdownNow(); }
    }
    @Test void nonPlatformCannotReadValidateWritePreviewOrCatalog() {
        Map<String, Object> first = create(); authority.context = new CityBoardDraftAuthority.Context("merchant", Collections.singleton("test-region"), false);
        fails("PLATFORM_EDITOR_REQUIRED", () -> service.catalog()); fails("PLATFORM_EDITOR_REQUIRED", () -> service.reopen("test-board"));
        fails("PLATFORM_EDITOR_REQUIRED", () -> service.validate(command("v", 0, null))); fails("PLATFORM_EDITOR_REQUIRED", () -> service.save(command("x", 0, null)));
        fails("PLATFORM_EDITOR_REQUIRED", () -> service.preview(previewCommand(first)));
    }
    @Test void regionRevocationBlocksReplayAndReads() {
        create(); authority.context = new CityBoardDraftAuthority.Context("test-platform", Collections.singleton("other-region"), true);
        fails("REGION_FORBIDDEN", () -> service.save(command("create", 0, null))); fails("REGION_FORBIDDEN", () -> service.reopen("test-board"));
    }
    @Test void merchantFactsAndPlayerStrategyCannotBeWritten() {
        Map<String, Object> cmd = command("bad", 0, null); map(cmd.get("payload")).put("merchant_operational_fact", object("safe", true));
        fails("UNKNOWN_OR_MISSING_FIELD", () -> service.save(cmd));
        Map<String, Object> other = command("bad", 0, null); map(other.get("payload")).put("classification", "player_strategy");
        fails("PLATFORM_RULE_ONLY", () -> service.save(other));
    }
    @Test void missingExplicitValueNegativeFloatAndBudgetOverflowReject() {
        for (Object invalid : Arrays.asList(null, -1, 1.5, "1")) {
            Map<String, Object> cmd = command("invalid", 0, null); map(map(cmd.get("payload")).get("rule")).put("taskRewardPoints", invalid);
            assertThrows(Failure.class, () -> service.save(cmd));
        }
        Map<String, Object> cmd = command("budget", 0, null); map(map(cmd.get("payload")).get("rule")).put("rewardBudgetPoints", 0);
        fails("REWARD_EXCEEDS_DRAFT_BUDGET", () -> service.save(cmd));
    }
    @Test void staleWrongKindUnapprovedAndWrongRegionReferencesReject() {
        for (String field : Arrays.asList("hash", "kind", "approved", "regionId")) {
            TestAuthority a = new TestAuthority(); Map<String, Object> ref = map(((List<?>) a.facts.get("references")).get(0));
            ref.put(field, "approved".equals(field) ? false : "wrong");
            fails("REFERENCE_INVALID", () -> service(source, a).save(command("bad", 0, null)));
        }
    }
    @Test void unsafeClosedUnlicensedMissingCapacityAndMismatchedTaskReject() {
        for (String field : Arrays.asList("safe", "open", "licensed", "authorized", "public", "capacity", "taskIds")) {
            TestAuthority a = new TestAuthority(); Map<String, Object> place = map(((List<?>) a.facts.get("places")).get(0));
            place.put(field, "capacity".equals(field) ? 0 : "taskIds".equals(field) ? Collections.emptyList() : false);
            assertThrows(Failure.class, () -> service(source, a).save(command("bad", 0, null)));
        }
    }
    @Test void missingOrDuplicatePlacesRejectWithoutWrites() throws Exception {
        Map<String, Object> cmd = command("bad", 0, null); map(cmd.get("payload")).put("servicePoints", Collections.emptyList());
        fails("SERVICE_POINTS_REQUIRED", () -> service.save(cmd));
        map(cmd.get("payload")).put("servicePoints", Arrays.asList(object("placeId", "test-place", "role", "SERVICE"), object("placeId", "test-place", "role", "SERVICE")));
        fails("DUPLICATE_PLACE", () -> service.save(cmd)); assertEquals(0, count("city_board_draft"));
    }
    @Test void invalidWindowTimezoneAndPolicyLimitsReject() {
        Map<String, Object> cmd = command("bad", 0, null); map(cmd.get("payload")).put("timezone", "not-a-zone");
        fails("INVALID_TIME", () -> service.save(cmd));
        map(cmd.get("payload")).put("timezone", "UTC"); map(cmd.get("payload")).put("closesAt", "2026-10-01T00:00:00Z");
        fails("INVALID_WINDOW", () -> service.save(cmd));
        Map<String, Object> limits = command("limits", 0, null); map(map(limits.get("payload")).get("rule")).put("actionBudget", 101);
        fails("INTEGER_OUT_OF_RANGE", () -> service.save(limits));
    }
    @Test void disabledModeFailsBeforeAnyAuthorityOrStorageAccess() {
        fails("CITY_DRAFT_DISABLED", () -> new CityBoardDraftService().save(command("x", 0, null)));
        fails("CITY_DRAFT_DISABLED", () -> new CityBoardDraftService().reopen("test-board"));
    }
    @Test void corruptedStoredContentIsNotPreviewed() throws Exception {
        create(); try (Connection c = source.getConnection()) { c.createStatement().executeUpdate("UPDATE city_board_draft SET snapshot_json=REPLACE(snapshot_json,'隔离测试棋盘','corrupted')"); }
        fails("CORRUPT_DRAFT", () -> service.reopen("test-board"));
    }

  @Test void concurrentFirstCreatesNeverOverwrite() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i=0; i<2; i++) { final String request="new"+i; futures.add(pool.submit(() -> {
        start.await(); try { service.save(command(request,0,null)); return "ok"; } catch(Failure e) { return e.getMessage(); }
      })); }
      start.countDown(); List<String> results=Arrays.asList(futures.get(0).get(10,TimeUnit.SECONDS), futures.get(1).get(10,TimeUnit.SECONDS));
      assertEquals(1, Collections.frequency(results,"ok")); assertEquals(1,count("city_board_draft")); assertEquals(1,count("city_board_draft_receipt"));
      assertEquals(1,((Number)service.reopen("test-board").get("version")).intValue());
    } finally { pool.shutdownNow(); }
  }
  @Test void concurrentRequestIdAcrossDifferentDraftsIsAtomic() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for(int i=0;i<2;i++) { final String draft="board"+i; futures.add(pool.submit(() -> {
        Map<String,Object> cmd=command("same-request",0,null); cmd.put("draftId",draft);
        start.await(); try { service.save(cmd); return "ok"; } catch(Failure e) { return e.getMessage(); }
      })); }
      start.countDown(); List<String> results=Arrays.asList(futures.get(0).get(10,TimeUnit.SECONDS), futures.get(1).get(10,TimeUnit.SECONDS));
      assertEquals(1,Collections.frequency(results,"ok")); assertEquals(1,count("city_board_draft")); assertEquals(1,count("city_board_draft_receipt"));
    } finally { pool.shutdownNow(); }
  }
  @Test void receiptFailureRollsBackCreateAndEdit() throws Exception {
    try(Connection c=source.getConnection(); Statement s=c.createStatement()) {
      s.execute("ALTER TABLE city_board_draft_receipt ADD CONSTRAINT reject_test_receipt CHECK (request_id <> 'reject')");
    }
    assertThrows(Failure.class,()->service.save(command("reject",0,null)));
    assertEquals(0,count("city_board_draft")); assertEquals(0,count("city_board_draft_receipt"));
    Map<String,Object> first=create(), update=command("reject",1,(String)first.get("payloadHash"));
    map(update.get("payload")).put("name","must rollback");
    assertThrows(Failure.class,()->service.save(update)); assertEquals(first,service.reopen("test-board"));
    assertEquals(1,count("city_board_draft_receipt"));
  }
  @Test void replaySurvivesAuthorityReferenceChangeButNewRequestFails() {
    Map<String,Object> first=create();
    map(((List<?>)authority.facts.get("references")).get(0)).put("approved",false);
    assertEquals(first,service.save(command("create",0,null)));
    fails("REFERENCE_INVALID",()->service.save(command("new",1,(String)first.get("payloadHash"))));
  }
  @Test void differentActorCannotReuseReceiptToBypassValidation() {
    create(); authority.context=new CityBoardDraftAuthority.Context("other-editor",Collections.singleton("test-region"),true);
    fails("CITY_DRAFT_CONFLICT",()->service.save(command("create",0,null)));
  }
  @Test void platformEditorCannotMoveDraftToAnotherAuthorizedRegion() {
    Map<String,Object> first=create(); authority.context=new CityBoardDraftAuthority.Context("test-platform",new HashSet<>(Arrays.asList("test-region","other-region")),true);
    Map<String,Object> update=command("move",1,(String)first.get("payloadHash")); map(update.get("payload")).put("regionId","other-region");
    fails("REGION_IMMUTABLE",()->service.save(update)); assertEquals(first,service.reopen("test-board"));
  }
}
