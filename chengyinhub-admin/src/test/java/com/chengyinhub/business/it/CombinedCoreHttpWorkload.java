package com.chengyinhub.business.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed chapter-command workload. No login bypass, business response stub or app launcher. */
final class CombinedCoreHttpWorkload implements AutoCloseable {
    static final int[] CONCURRENCY = {1, 3, 6};
    static final String COMMAND = "/api/club/lead/unlock-chapter";
    static final String RECEIPT = "/api/club/lead/unlock-chapter-receipt";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate db;
    private final JsonNode identities;
    private final String origin;
    private final ExecutorService workers = new ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(6), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1);
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    final List<Map<String,Object>> waves = new ArrayList<>();
    final List<Map<String,Object>> additionalReadWindows = new ArrayList<>();
    final CountDownLatch entered = new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicInteger verifiedResponses = new java.util.concurrent.atomic.AtomicInteger();
    private Map<String,String> firstFacts;
    private Map<String,Map<String,Object>> firstStates;

    CombinedCoreHttpWorkload(JdbcTemplate db, JsonNode identities, int port) {
        assertTrue(port > 0 && port <= 65535);
        this.db = db; this.identities = identities; this.origin = "http://127.0.0.1:" + port;
        deadlines.setRemoveOnCancelPolicy(true); deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        assertNotEquals(identities.path("a").path("memberId").asLong(), identities.path("b").path("memberId").asLong());
    }

    /** Original first-wave SQL and held witness, before starting the HTTP-entry wait budget. */
    void prepareFirstWave(Runnable heldWitness) throws Exception {
        assertNull(firstFacts, "Do not replace an original prepared wave");
        assertTrue(waves.isEmpty()); assertEquals(1L, entered.getCount());
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 24; i++) keys.add(String.format(Locale.ROOT, "CAPACITY_%02d", i));
        Map<String,String> before = new HashMap<>();
        Map<String,Map<String,Object>> states = new HashMap<>();
        captureBefore(keys, before, states);
        heldWitness.run();
        firstFacts = before; firstStates = states; // Publish only a complete original preparation.
    }

    void run(Runnable heldWitness) throws Exception {
        assertNotNull(firstFacts, "Prepare the original SQL facts and held witness before HTTP submission");
        assertNotNull(firstStates);
        Map<String,String> preparedFacts = firstFacts;
        Map<String,Map<String,Object>> preparedStates = firstStates;
        firstFacts = null; firstStates = null; // Consume once; a failed run cannot silently replay.
        for (int wave = 0; wave < CONCURRENCY.length; wave++) {
            int concurrency = CONCURRENCY[wave];
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 24; i++) keys.add(String.format(Locale.ROOT, "CAPACITY_%02d", wave * 24 + i));
            Map<String, String> before = new HashMap<>();
            Map<String, Map<String,Object>> states = new HashMap<>();
            if (wave == 0) {
                assertEquals(new HashSet<>(keys), preparedFacts.keySet());
                assertEquals(new HashSet<>(keys), preparedStates.keySet());
                before.putAll(preparedFacts); states.putAll(preparedStates);
            } else {
                captureBefore(keys, before, states);
                heldWitness.run();
            }
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("concurrency", concurrency); row.put("newIntents", 24);
            waves.add(row);
            var writes = new LinkedHashMap<String,Object>(); row.put("write", writes); curve(keys, concurrency, true, writes);
            // HTTP success alone is provisional: all original SQL identities/effects must match.
            for (String key : keys) {
                JsonNode request = identities.path(key); assertApplied(request);
                var expected = states.get(key); expected.put("current_chapter_id", request.path("targetChapterId").asLong());
                assertEquals(expected, db.queryForMap("SELECT * FROM club_lead_state WHERE activity_id=?", request.path("activityId").asLong()),
                        "one chapter CAS is the only permitted state change");
            }
            String committed = allGame();
            var reads = new LinkedHashMap<String,Object>(); row.put("readReplayForeign", reads); curve(keys, concurrency, false, reads);
            assertEquals(committed, allGame(), "read/replay/foreign rejection must have zero additional effects");
            for (String key : keys) {
                assertApplied(identities.path(key)); assertNotEquals(before.get(key), fact(identities.path(key)));
            }
            heldWitness.run(); row.put("factsVerified", true);
        }
    }

    private void captureBefore(List<String> keys, Map<String,String> before,
                               Map<String,Map<String,Object>> states) throws IOException {
        for (String key : keys) {
            JsonNode request = identities.path(key);
            assertTrue(request.path("commandId").asText().matches("[A-Za-z0-9:_-]{1,80}"));
            assertEquals(request.path("expectedChapterId").asLong(), chapter(request));
            assertEquals(0, receipts(request).size()); before.put(key, fact(request));
            states.put(key, new LinkedHashMap<>(db.queryForMap("SELECT * FROM club_lead_state WHERE activity_id=?",
                    request.path("activityId").asLong())));
        }
    }

    static AssertionError entryFailure(Future<?> request) throws InterruptedException {
        AssertionError missing = new AssertionError("Original core HTTP request did not enter its fixed window");
        if (request.isDone()) {
            try { request.get(); }
            catch (ExecutionException failed) { missing.initCause(failed.getCause() == null ? failed : failed.getCause()); }
            catch (CancellationException cancelled) { missing.initCause(cancelled); }
        }
        return missing; // No extra wait, cancellation, retry, response stub or public diagnostic schema.
    }

    void readOnlyWindow(String phase, Runnable heldWitness) throws Exception {
        assertTrue(Set.of("MAP_HTTP_HELD", "DISCONNECT", "BAD_RESPONSE", "RECOVERED").contains(phase));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 24; i++) keys.add(String.format(Locale.ROOT, "CAPACITY_%02d", i));
        String before = allGame(); heldWitness.run();
        var window = new LinkedHashMap<String,Object>(); window.put("phase", phase); additionalReadWindows.add(window);
        var reads = new LinkedHashMap<String,Object>(); window.put("curve", reads); curve(keys, 6, false, reads);
        assertEquals(before, allGame()); heldWitness.run(); window.put("factsVerified", true);
    }

    private void curve(List<String> keys, int concurrency, boolean write, Map<String,Object> result) throws Exception {
        long start = System.nanoTime();
        List<Map<String,Object>> records = new ArrayList<>();
        result.put("records", records); result.put("configuredConcurrency", concurrency);
        result.put("latencyScope", "loopback actual servlet request; no production SLO");
        try {
        // Closed loop: at most concurrency submitted futures, no 24-task waiting queue.
        for (int offset = 0; offset < keys.size(); offset += concurrency) {
            List<Future<Map<String,Object>>> batch = new ArrayList<>();
            for (int i = offset; i < Math.min(keys.size(), offset + concurrency); i++) {
                String kind = write ? "command" : new String[]{"replay", "receipt", "foreign"}[i % 3];
                String key = keys.get(i); batch.add(workers.submit(() -> observe(key, kind)));
            }
            for (Future<Map<String,Object>> future : batch) records.add(future.get(6, TimeUnit.SECONDS));
            assertTrue(records.stream().allMatch(row -> Set.of("SUCCESS", "LEGAL_AUTH_REJECTION").contains(row.get("category"))),
                    "unknown command outcome stops this fixed stage; preserve original identity and never retry");
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(45),
                    "fixed observation bound expired; original commands must remain UNKNOWN, never retried");
        }
        assertEquals(24, records.size());
        assertTrue(records.stream().allMatch(row -> Set.of("SUCCESS", "LEGAL_AUTH_REJECTION").contains(row.get("category"))),
                "HTTP observation failed; original command identities remain in the owned SQL store");
        } finally {
            result.put("durationSeconds", (System.nanoTime() - start) / 1e9); result.put("requestsRecorded", records.size());
        }
    }

    Map<String,Object> observe(String key, String kind) {
        long start = System.nanoTime(); JsonNode request = identities.path(key);
        String category = "UNKNOWN";
        try {
            JsonNode body = http(request, kind);
            if (kind.equals("foreign")) {
                assertNotEquals(200, body.path("code").asInt());
                assertTrue(body.path("data").isMissingNode() || body.path("data").isNull());
                assertEquals("只有当前队长可以放行章节", body.path("msg").asText(),
                        "generic 500/timeout is not an authorization refusal proof");
                category = "LEGAL_AUTH_REJECTION";
            } else {
                assertEquals(200, body.path("code").asInt());
                assertEquals(request.path("commandId").asText(), body.path("data").path("commandId").asText());
                assertEquals(request.path("targetChapterId").asLong(), body.path("data").path("targetChapterId").asLong());
                assertEquals(request.path("expectedChapterId").asLong(), body.path("data").path("expectedChapterId").asLong());
                assertEquals(3, body.path("data").size());
                category = "SUCCESS";
            }
        } catch (HttpStatusFailure status) { category = status.status >= 500 ? "SYSTEM_FAILURE" : "UNKNOWN"; }
        catch (SocketTimeoutException timeout) { category = "TRANSPORT_TIMEOUT"; }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { category = "CONTRACT_MISMATCH"; }
        catch (IOException transport) { category = "TRANSPORT_ERROR"; }
        catch (AssertionError | RuntimeException contract) { category = "UNKNOWN"; }
        if (category.equals("SUCCESS") || category.equals("LEGAL_AUTH_REJECTION")) verifiedResponses.incrementAndGet();
        // Fixed labels and original identity hashes only; never serialize a response, bearer or cause.
        return Map.of("kind", kind, "intentSha256", sha(request.path("commandId").asText().getBytes(StandardCharsets.UTF_8)),
                "category", category, "elapsedSeconds", (System.nanoTime() - start) / 1e9);
    }

    private JsonNode http(JsonNode request, String kind) throws IOException {
        boolean receipt = kind.equals("receipt");
        Map<String,String> fields = new LinkedHashMap<>();
        fields.put("activityId", request.path("activityId").asText()); fields.put("commandId", request.path("commandId").asText());
        if (!receipt) {
            fields.put("expectedChapterId", request.path("expectedChapterId").asText());
            fields.put("targetChapterId", request.path("targetChapterId").asText());
        }
        String form = fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + (receipt ? RECEIPT + "?" + form : COMMAND))
                .openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(1000); connection.setReadTimeout(4000);
        connection.setRequestProperty("Authorization", "Bearer " + identities.path(kind.equals("foreign") ? "b" : "a").path("token").asText());
        active.add(connection);
        entered.countDown();
        ScheduledFuture<?> stop = deadlines.schedule(connection::disconnect, 5, TimeUnit.SECONDS);
        try {
            if (!receipt) {
                byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new HttpStatusFailure(status);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                assertTrue(bytes.length <= 1024 * 1024, "bounded command response required");
                return JSON.readTree(bytes);
            }
        } finally { stop.cancel(false); connection.disconnect(); active.remove(connection); }
    }
    private static final class HttpStatusFailure extends IOException {
        final int status;
        HttpStatusFailure(int status) { super("CONTROLLED_HTTP_STATUS_NOT_EXPECTED"); this.status = status; }
    }

    private long chapter(JsonNode request) {
        return db.queryForObject("SELECT current_chapter_id FROM club_lead_state WHERE activity_id=?", Long.class, request.path("activityId").asLong());
    }
    private List<Map<String,Object>> receipts(JsonNode request) {
        return db.queryForList("SELECT * FROM game_command_receipt WHERE activity_id=? AND actor_member_id=? AND request_id=?",
                request.path("activityId").asLong(), identities.path("a").path("memberId").asLong(),
                "lead:" + sha(request.path("commandId").asText().getBytes(StandardCharsets.UTF_8)).substring(0, 48));
    }
    private void assertApplied(JsonNode request) throws IOException {
        assertEquals(request.path("targetChapterId").asLong(), chapter(request));
        var rows = receipts(request); assertEquals(1, rows.size()); var row = rows.get(0);
        assertEquals("APPLIED", row.get("outcome")); assertEquals("LEAD_UNLOCK_CHAPTER", row.get("action"));
        assertEquals("CLUB_LEAD", row.get("actor_perspective"));
        String identity = request.path("activityId").asText() + ":" + identities.path("a").path("memberId").asText()
                + ":" + request.path("expectedChapterId").asText() + ":" + request.path("targetChapterId").asText();
        assertEquals(sha(identity.getBytes(StandardCharsets.UTF_8)), row.get("request_hash"));
        JsonNode terminal = JSON.readTree((String) row.get("result_json"));
        assertEquals(200, terminal.path("code").asInt());
        assertEquals(request.path("commandId").asText(), terminal.path("data").path("commandId").asText());
    }
    private String fact(JsonNode request) throws IOException {
        return sha(CrossDomainCombinedLoadMySqlIT.completeFactGroups(Map.of("state", db.queryForList("SELECT * FROM club_lead_state WHERE activity_id=?",
                request.path("activityId").asLong()), "receipt", receipts(request))));
    }
    private String allGame() throws IOException {
        Map<String,List<Map<String,Object>>> rows = new TreeMap<>();
        for (String table : List.of("club_lead_state", "game_command_receipt", "business_audit_event"))
            rows.put(table, db.queryForList("SELECT * FROM `" + table + "` ORDER BY id"));
        return sha(CrossDomainCombinedLoadMySqlIT.completeFactGroups(rows));
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    @Override public void close() throws Exception {
        active.forEach(HttpURLConnection::disconnect); workers.shutdownNow(); deadlines.shutdownNow();
        assertTrue(workers.awaitTermination(6, TimeUnit.SECONDS)); assertTrue(deadlines.awaitTermination(6, TimeUnit.SECONDS));
        assertTrue(active.isEmpty(), "all owned HTTP attempts must physically exit");
    }
}
