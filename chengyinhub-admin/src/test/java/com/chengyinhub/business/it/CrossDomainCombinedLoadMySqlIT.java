package com.chengyinhub.business.it;

import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.RuoYiApplication;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.quartz.util.CrossDomainSchedulerStage;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.support.TestPropertySourceUtils;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.security.*;
import java.util.zip.ZipFile;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** New representative combination; no real payment/provider HTTP or production capacity assertion. */
@SpringBootTest(classes = RuoYiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = CrossDomainCombinedLoadMySqlIT.Initializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ResourceLock("t19-process-global-http-ai-map")
class CrossDomainCombinedLoadMySqlIT {
    @Autowired ConfigurableApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path privateDirectory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper FACT_JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final List<String> NO_CASH = List.of("oms_order", "oms_order_item", "coop_order",
            "registration_entitlement", "sms_coupon_history", "member_capability", "merchant_subscription",
            "ums_member_withdrawal", "member_cash_withdraw");

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext application) {
            try {
                requirePreparedTestClasspath(P0AcceptanceSchema.repoRoot().toRealPath(), System.getProperty("surefire.test.class.path"));
            } catch (IOException failure) {
                throw new IllegalStateException("PREPARED_TEST_CLASSPATH_REQUIRED", failure);
            }
            new GenericGameplayHttpE2eIT.LocalInitializer().initialize(application);
            configureBoundedPool(application);
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(application,
                    "chengyin.jobs.enabled=false",
                    "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=" + System.getenv("CHENGYIN_HTTP_E2E_REDIS_PORT"),
                    "spring.data.redis.database=15", "spring.data.redis.password=");
        }

        static void configureBoundedPool(ConfigurableApplicationContext application) {
            // The original druid profile has initialSize=5/minIdle=10. Both must fit the
            // fixed three-connection cap before the real datasource is initialized.
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(application,
                    "spring.datasource.druid.initialSize=0", "spring.datasource.druid.minIdle=0",
                    "spring.datasource.druid.maxActive=3", "spring.datasource.druid.maxWait=5000",
                    "spring.datasource.druid.socketTimeout=5000");
        }
    }

    /** Compare original bytes only; the existing pinned codec/owner validates their entries and source binding. */
    static void requirePreparedTestClasspath(Path root, String actual) throws IOException {
        assertNotNull(actual); byte[] bytes = actual.getBytes(StandardCharsets.UTF_8);
        assertTrue(bytes.length > 0 && bytes.length < 1024 * 1024);
        assertTrue(root.isAbsolute() && root.equals(root.toRealPath()));
        Path directory = root.resolve("chengyinhub-admin/target/provider-runtime");
        assertEquals(directory, directory.toRealPath());
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        assertEquals(Files.getOwner(root), Files.getOwner(directory));
        for (String phase : List.of("online", "offline")) {
            Path file = directory.resolve(phase + ".classpath");
            assertTrue(Files.isRegularFile(file) && !Files.isSymbolicLink(file) && file.equals(file.toRealPath()));
            assertTrue(Files.size(file) > 0 && Files.size(file) < 1024 * 1024);
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(Files.getOwner(root), Files.getOwner(file));
            assertTrue(Arrays.equals(bytes, Files.readAllBytes(file)), "Actual Surefire classpath must retain both original probe byte sequences");
        }
    }

    @Timeout(150)
    @Test
    void realCommandsRemainIsolatedAcrossHeldProvidersAndOriginalEconomicRecovery() throws Throwable {
        Map<String,Object> evidence = new LinkedHashMap<>();
        evidence.put("format", "CONTROLLED_COMBINATION_V1"); evidence.put("result", "NOT_COMPLETED");
        evidence.put("wholeSystemCapacityAccepted", false); evidence.put("q4q8Complete", false);
        List<Map<String,Object>> observations = Collections.synchronizedList(new ArrayList<>()); evidence.put("observations", observations);
        List<Map<String,Object>> samples = Collections.synchronizedList(new ArrayList<>()); evidence.put("samples", samples);
        Path identitiesPath = privateDirectory.resolve("identities.json");
        ExecutorService foreground = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
        ExecutorService delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        Throwable primary = null;
        var storage = new CrossDomainStorageFixture(); var transport = new CrossDomainTransportStage();
        try (storage; transport) {
            assertFalse(com.chengyinhub.common.config.RestoreDispatchGuard.isRestoreMode(), "normal-mode isolated combination only");
            String coreSchema = jdbc.queryForObject("SELECT DATABASE()", String.class);
            assertTrue(coreSchema.matches("cy_e2e_w1_http_[0-9_]+"));
            String serverUuid = jdbc.queryForObject("SELECT @@server_uuid", String.class);
            for (String table : NO_CASH) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class));
            // Shared review hunk only extracts the original fixed seed. Its login/actors/CAS inputs remain original.
            JsonNode identities = GameplayProcessRecoveryChild.seedCombination(context, jdbc, identitiesPath);
            for (String actor : List.of("a", "b")) {
                var member = jdbc.queryForMap("SELECT balance,point,role,user_type,status,del_flag FROM ums_member WHERE id=?",
                        identities.path(actor).path("memberId").asLong());
                assertEquals(0, ((java.math.BigDecimal) member.get("balance")).compareTo(java.math.BigDecimal.ZERO));
                assertEquals(0, ((Number) member.get("point")).longValue()); assertEquals("player", member.get("role"));
                assertEquals(1, ((Number) member.get("user_type")).intValue()); assertEquals(1, ((Number) member.get("status")).intValue());
                assertEquals(0, ((Number) member.get("del_flag")).intValue());
            }
            String actorBefore = actorDigest(identities);
            evidence.putAll(identity(coreSchema, serverUuid));
            var appPool = context.getBean("masterDataSource", DruidDataSource.class);
            assertEquals(3, appPool.getMaxActive());
            assertEquals(0, appPool.getInitialSize()); assertEquals(0, appPool.getMinIdle());
            storage.open(context.getBean(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class));
            storage.pool.setMaxActive(3); // narrow the existing fixture's 12 cap, never enlarge it
            assertEquals(serverUuid, storage.serverUuid); assertNotEquals(coreSchema, storage.schema);
            assertEquals(System.getenv("CHENGYIN_HTTP_E2E_REDIS_PORT"), System.getenv("CHENGYIN_AI_REDIS_IT_PORT"));
            assertEquals("15", System.getenv("CHENGYIN_AI_REDIS_IT_DATABASE"));
            transport.open();
            samples.add(sample("BEFORE", appPool, storage.pool, storage.admin, coreSchema, storage.schema));
            evidence.put("economicSchema", storage.schema); evidence.put("originalEconomicIdentity", storage.original);
            evidence.put("poolCaps", Map.of("application", 3, "economicFixture", 3, "domainPoolMaximumSum", 6));
            evidence.put("runtimeObservations", Map.of("scope", "existing HTTP owner; not an inferred Docker app identity",
                    "javaMaxHeapBytes", Runtime.getRuntime().maxMemory(), "cgroupMemory", resourceFile("memory.max"),
                    "cgroupCpu", resourceFile("cpu.max")));
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (var core = new CombinedCoreHttpWorkload(jdbc, identities, port);
                 var scheduler = new CrossDomainSchedulerStage(storage.pool, storage.schema, storage.original,
                         storage.url, storage.user, storage.password)) {
              try {
                evidence.put("coreWaves", core.waves); evidence.put("additionalReadWindows", core.additionalReadWindows);
                var left = storage.photo(transport.client(0), transport::image);
                var right = storage.photo(transport.client(1), transport::image);
                var normal = storage.command(1); var prepared = storage.tx.execute(tx -> left.prepare(normal));
                assertNotNull(prepared);
                var baseline = left.executeOnce(CrossDomainStorageFixture.MEMBER, prepared.intentId(), normal.requestFingerprint());
                assertEquals(PhotoCheckIntentState.RESULT_READY, baseline.state()); assertEquals("RECEIVED", baseline.providerState());
                assertFalse(baseline.result().isDegraded());
                storage.broadcast("inapp"); storage.notifications.runDueScheduled();
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM im_message", Integer.class));
                TopicRouteState initial = storage.route.queryState(CrossDomainStorageFixture.MEMBER, 80L, null);
                assertEquals("ACTIVE", initial.getStatus()); assertEquals(7, initial.getVersion());
                String routeBefore = storage.db.queryForObject("SELECT state_json FROM topic_route_session", String.class);
                observations.add(storage.assertTransactionOracleDetectsParentTransaction());
                transport.beginFault();
                storage.poisonId = storage.broadcast("subscribe"); storage.healthyId = storage.broadcast("inapp");
                Future<?> notification = delivery.submit(storage.notifications::runDueScheduled);
                assertTrue(storage.poisonEntered.await(5, TimeUnit.SECONDS));
                List<PhotoCheckIntentCommand> commands = new ArrayList<>(); List<PhotoCheckIntentView> original = new ArrayList<>();
                List<Future<PhotoCheckIntentView>> pending = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    var service = i < 2 ? left : right; var command = storage.command(i + 2); commands.add(command);
                    var intent = storage.preparePhoto(service, command); assertNotNull(intent); original.add(intent);
                    pending.add(transport.callers.submit(() -> service.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint())));
                }
                boolean aiHandlersEntered = transport.aiEntered.await(5, TimeUnit.SECONDS);
                if (!aiHandlersEntered) throw CrossDomainTransportStage.aiEntryFailure(pending, transport.providerFailures);
                assertTrue(aiHandlersEntered);
                for (var request : pending) {
                    var unknown = request.get(6, TimeUnit.SECONDS);
                    assertEquals(PhotoCheckIntentState.RESULT_READY, unknown.state()); assertEquals("UNKNOWN", unknown.providerState());
                    assertTrue(unknown.result().isDegraded());
                }
                Runnable stillHeld = () -> {
                    assertEquals(1, transport.releaseAi.getCount()); assertEquals(1, storage.releasePoison.getCount());
                    assertEquals(4, transport.mapActive.get()); assertEquals(4, transport.aiServerExited.getCount());
                    transport.assertHeldAfterAiCallerTimeouts();
                    transport.retireImageSource(pending);
                    samples.add(sample("HELD", appPool, storage.pool, storage.admin, coreSchema, storage.schema));
                };
                // Start the real HTTP writes while ALL three original provider boundaries remain held.
                core.prepareFirstWave(stillHeld);
                Future<?> coreRequests = foreground.submit(() -> { try { core.run(stillHeld); } catch (Exception e) { throw new CompletionException(e); } });
                boolean coreEntered = core.entered.await(2, TimeUnit.SECONDS);
                if (!coreEntered) throw CombinedCoreHttpWorkload.entryFailure(coreRequests);
                assertTrue(coreEntered);
                assertEquals("SUCCESS", foreground.submit(() -> storage.callbacks.payment(storage.paymentPayload()).result()).get(5, TimeUnit.SECONDS));
                storage.assertPaymentReadback();
                String paid = economicDigest(storage);
                var wrong = new HashMap<>(storage.paymentPayload()); wrong.put("total", "1991");
                foreground.submit(() -> assertThrows(IllegalStateException.class, () -> storage.callbacks.payment(wrong))).get(5, TimeUnit.SECONDS);
                assertEquals(paid, economicDigest(storage), "wrong amount must change no economic/entitlement/fact row");
                assertEquals("SUCCESS", foreground.submit(() -> storage.callbacks.payment(storage.paymentPayload()).result()).get(5, TimeUnit.SECONDS));
                assertEquals(paid, economicDigest(storage), "same immutable verified fact cannot grant twice");
                scheduler.holdOriginalWorkAndLoseLeadership();
                scheduler.finishLateWorkerAndKillOnlyOwnedSuccessor();
                assertEquals(1, scheduler.effects()); assertEquals(3, scheduler.attempts());
                coreRequests.get(45, TimeUnit.SECONDS); stillHeld.run();
                var sql = storage.observeParentTransactions(); assertEquals(0, sql.openTransactions()); observations.add(sql.evidence());
                assertEquals("CURRENT_PHYSICAL_WITNESS_IN_CACHE", sql.freshness().get("result"));
                TopicRouteState loaded = storage.route.queryState(CrossDomainStorageFixture.MEMBER, 80L, null);
                assertEquals(initial.getSessionId(), loaded.getSessionId()); assertEquals(initial.getVersion(), loaded.getVersion());
                assertThrows(IllegalStateException.class, () -> storage.route.queryState(CrossDomainStorageFixture.MEMBER + 1, 80L, null));
                assertEquals(routeBefore, storage.db.queryForObject("SELECT state_json FROM topic_route_session", String.class));
                assertEquals(0, storage.db.queryForObject("SELECT COUNT(*) FROM topic_route_action", Integer.class));
                notification.get(15, TimeUnit.SECONDS);
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM official_broadcast_log WHERE broadcast_id=? AND channel='inapp' AND delivery_state='SUCCESS'",
                        Integer.class, storage.healthyId));
                observations.add(Map.of("phase", "HELD_COMBINATION", "coreHttpRequests", 144, "callbackResult", "SUCCESS",
                        "wrongAmountZeroEffects", true, "singleGrant", true, "schedulerEffects", scheduler.effects(),
                        "schedulerAttempts", scheduler.attempts(), "aiPhysicalHandlersHeld", 4, "mapPhysicalSlotsHeld", 4,
                        "notificationHandlerHeld", true));
                storage.releasePoison.countDown(); notification.get(8, TimeUnit.SECONDS); transport.finishFault();
                for (int i = 0; i < 4; i++) {
                    var command = commands.get(i); var intent = original.get(i);
                    assertEquals("UNKNOWN", left.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint()).providerState());
                }
                assertEquals(5, transport.aiRequests.get());
                String unknownBefore = unknownDigest(storage);
                Map<Long,String> retainedUnknown = new ConcurrentHashMap<>();
                for (var intent : original) retainedUnknown.put(intent.intentId(), retainedDigest(storage, intent.intentId()));
                try (var responses = new CombinedControlledProviders()) {
                    responses.attachNotice(storage);
                    var cities = responses.cities();
                    responses.mode(CombinedControlledProviders.Mode.HOLD_MAP);
                    List<Future<String>> maps = new ArrayList<>();
                    for (int i = 0; i < 4; i++) { final int index = i; maps.add(transport.callers.submit(() -> cities.resolveCity(120D + index / 1000D, 30D))); }
                    assertTrue(responses.mapEntered.await(5, TimeUnit.SECONDS));
                    assertNull(responses.cities().resolveCity(122D, 32D));
                    assertEquals(4, responses.map.get(), "overflow must not become a fifth physical map HTTP request");
                    Runnable mapHeld = () -> { assertEquals(1, responses.releaseMap.getCount()); assertEquals(4, responses.active.get()); };
                    core.readOnlyWindow("MAP_HTTP_HELD", mapHeld);
                    responses.releaseMap.countDown();
                    for (var result : maps) assertEquals("杭州市", result.get(5, TimeUnit.SECONDS));
                    for (var mode : List.of(CombinedControlledProviders.Mode.DISCONNECT, CombinedControlledProviders.Mode.BAD_RESPONSE,
                            CombinedControlledProviders.Mode.NORMAL)) {
                        responses.mode(mode); String phase = mode == CombinedControlledProviders.Mode.NORMAL ? "RECOVERED" : mode.name();
                        var photo = storage.photo(responses.aiClient(), () -> CrossDomainTransportStage.SMALL_IMAGE);
                        int number = mode == CombinedControlledProviders.Mode.DISCONNECT ? 40 : mode == CombinedControlledProviders.Mode.BAD_RESPONSE ? 41 : 42;
                        var command = storage.command(number); var intent = storage.tx.execute(tx -> photo.prepare(command)); assertNotNull(intent);
                        int beforePosts = responses.ai.get();
                        int beforeNotices = responses.notice.get(); int beforeMaps = responses.map.get();
                        int beforeCore = core.verifiedResponses.get();
                        Future<PhotoCheckIntentView> photoCall = transport.callers.submit(() -> photo.executeOnce(
                                CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint()));
                        Future<String> mapCall = transport.callers.submit(() -> responses.cities().resolveCity(121D + number / 1000D, 31D));
                        long notice = storage.broadcast("subscribe");
                        Future<?> noticeCall = delivery.submit(storage.notifications::runDueScheduled);
                        if (mode != CombinedControlledProviders.Mode.NORMAL)
                            assertTrue(responses.failureEntered.await(3, TimeUnit.SECONDS), "all three real local provider requests must be entered");
                        Future<?> reads = foreground.submit(() -> {
                            try { core.readOnlyWindow(phase, () -> {
                                assertEquals(unknownBefore, unknownDigest(storage)); assertRetained(storage, retainedUnknown);
                            }); }
                            catch (Exception failure) { throw new CompletionException(failure); }
                        });
                        try {
                            if (mode != CombinedControlledProviders.Mode.NORMAL) {
                                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                                while (core.verifiedResponses.get() == beforeCore) { assertTrue(System.nanoTime() < end); Thread.sleep(5); }
                                assertEquals(3, responses.active.get(), "core servlet progressed while AI/map/notice responses physically held");
                            }
                        } finally { responses.releaseFailure.countDown(); }
                        var result = photoCall.get(5, TimeUnit.SECONDS);
                        assertEquals(PhotoCheckIntentState.RESULT_READY, result.state());
                        assertEquals(mode == CombinedControlledProviders.Mode.NORMAL ? "RECEIVED" : "UNKNOWN", result.providerState());
                        if (mode != CombinedControlledProviders.Mode.NORMAL) {
                            assertTrue(result.result().isDegraded()); retainedUnknown.put(intent.intentId(), retainedDigest(storage, intent.intentId()));
                        }
                        assertEquals(beforePosts + 1, responses.ai.get(), "POST must never automatically retry");
                        String city = mapCall.get(5, TimeUnit.SECONDS);
                        if (mode == CombinedControlledProviders.Mode.NORMAL) assertEquals("杭州市", city); else assertNull(city);
                        noticeCall.get(5, TimeUnit.SECONDS);
                        assertEquals(mode == CombinedControlledProviders.Mode.NORMAL ? "SUCCESS" : "UNKNOWN", storage.db.queryForObject(
                                "SELECT delivery_state FROM official_broadcast_log WHERE broadcast_id=? AND channel='subscribe'", String.class, notice));
                        reads.get(6, TimeUnit.SECONDS);
                        assertEquals(beforeNotices + 1, responses.notice.get(), "pending UNKNOWN notice must not be redispatched");
                        assertTrue(responses.map.get() > beforeMaps && responses.map.get() <= beforeMaps + 4,
                                "map GET retains original bounded retry policy; actual attempts are not merged with logical calls");
                        var replay = photo.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint());
                        assertEquals(intent.intentId(), replay.intentId()); assertEquals(result.providerState(), replay.providerState());
                        assertRetained(storage, retainedUnknown);
                        assertEquals(beforePosts + 1, responses.ai.get(), "read/replay never changes the original UNKNOWN identity or sends again");
                        observations.add(Map.of("phase", phase, "intentId", intent.intentId(), "noticeId", notice,
                                "providerState", result.providerState(), "providerCounters", responses.observation()));
                    }
                }
                assertEquals(unknownBefore, unknownDigest(storage)); storage.assertPaymentReadback(); assertEquals(paid, economicDigest(storage));
                assertRetained(storage, retainedUnknown); assertEquals(6, retainedUnknown.size());
                storage.assertRouteAccessFixture();
                var counterKeys = storage.db.queryForList("SELECT DISTINCT counter_key FROM ai_quota_reservation", String.class);
                assertEquals(1, counterKeys.size(), "fixed single-day observation; no counter/day reset or inferred refund across rollover");
                assertEquals("8", storage.redis.opsForValue().get(counterKeys.get(0)), "two received plus six original UNKNOWN remain charged");
                assertEquals(actorBefore, actorDigest(identities));
                for (String table : NO_CASH) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class));
                assertEquals(0, appPool.getActiveCount()); assertEquals(0, storage.pool.getActiveCount());
                evidence.put("coreHttpExpected", 240); evidence.put("economicAssertionsVerified", true);
                evidence.put("retainedUnknownIds", retainedUnknown.keySet().stream().sorted().toList());
                evidence.put("economicFactEvidence", Map.of("order", CrossDomainStorageFixture.ORDER, "orderId", 8,
                        "totalFen", 1990, "currency", "CNY", "verifiedFactId", storage.payment.id(),
                        "verifiedFactHash", storage.payment.hash(), "stableEffectSha256", paid,
                        "proofSource", "CONTROLLED_ORIGINAL_FIXTURE_NOT_PROVIDER_SIGNATURE"));
                samples.add(sample("RECOVERED", appPool, storage.pool, storage.admin, coreSchema, storage.schema));
                evidence.put("result", "OBSERVED_REPRESENTATIVE_COMBINATION");
              } catch (Throwable failure) { primary = failure; throw failure; }
              finally {
                storage.releasePoison.countDown(); transport.releaseAi.countDown(); transport.releaseMap.countDown();
                transport.releaseDisconnect.countDown(); transport.releaseCleanup.countDown();
                foreground.shutdownNow(); delivery.shutdownNow();
                try { assertTrue(foreground.awaitTermination(10, TimeUnit.SECONDS)); assertTrue(delivery.awaitTermination(10, TimeUnit.SECONDS)); }
                catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
              }
            }
        } catch (Throwable failure) {
            primary = failure; evidence.put("result", "FAIL_OR_UNKNOWN");
            evidence.put("photoPreparationFailure", storage.photoPrepareFailure);
            throw failure;
        }
        finally {
            Throwable cleanupFailure = null;
            foreground.shutdownNow(); delivery.shutdownNow();
            try {
                assertTrue(foreground.awaitTermination(10, TimeUnit.SECONDS)); assertTrue(delivery.awaitTermination(10, TimeUnit.SECONDS));
                Files.deleteIfExists(identitiesPath);
                if (primary == null) {
                    assertFalse(storage.created); assertTrue(storage.pool.isClosed());
                    assertTrue(transport.providers.isTerminated()); assertTrue(transport.callers.isTerminated());
                    evidence.put("innerOwnedCleanupVerified", true);
                }
            } catch (Throwable cleanup) { cleanupFailure = cleanup; evidence.put("result", "FAIL_OR_UNKNOWN"); }
            try {
                String encoded = JSON.writeValueAsString(evidence);
                assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 128 * 1024, "fixed bounded combination observation required");
                System.out.println("CONTROLLED_COMBINATION_OBSERVATIONS " + encoded);
            }
            catch (Throwable output) { if (cleanupFailure == null) cleanupFailure = output; else cleanupFailure.addSuppressed(output); }
            if (cleanupFailure != null) { if (primary != null) primary.addSuppressed(cleanupFailure); else throw cleanupFailure; }
        }
    }

    private Map<String,Object> identity(String schema, String uuid) throws Exception {
        String source = System.getenv("SOURCE_SHA"); assertNotNull(source); assertTrue(source.matches("[0-9a-f]{40}"));
        String run = System.getenv("GITHUB_RUN_ID"), attempt = System.getenv("GITHUB_RUN_ATTEMPT");
        assertNotNull(run); assertTrue(run.matches("[1-9][0-9]*")); assertNotNull(attempt); assertTrue(attempt.matches("[1-9][0-9]*"));
        Path jar = P0AcceptanceSchema.repoRoot().resolve("chengyinhub-admin/target/chengyinhub-admin.jar");
        assertTrue(Files.isRegularFile(jar) && !Files.isSymbolicLink(jar));
        Map<String,String> actualClasses = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Properties props = new Properties();
            try (var input = zip.getInputStream(zip.getEntry("BOOT-INF/classes/git.properties"))) { props.load(input); }
            assertEquals(source, props.getProperty("git.commit.id.full", props.getProperty("git.commit.id")));
            for (Class<?> type : List.of(RuoYiApplication.class, com.chengyinhub.web.controller.api.ApiClubLeadController.class)) {
                String path = type.getName().replace('.', '/') + ".class";
                try (var actual = type.getResourceAsStream("/" + path); var built = zip.getInputStream(zip.getEntry("BOOT-INF/classes/" + path))) {
                    assertNotNull(actual); assertNotNull(built);
                    String hash = streamSha(actual); assertEquals(hash, streamSha(built)); actualClasses.put(type.getName(), hash);
                }
            }
        }
        Map<String,Object> identity = new LinkedHashMap<>();
        identity.putAll(Map.of("sourceSha", source, "runId", run, "runAttempt", attempt, "schema", schema, "serverUuid", uuid,
                "applicationEntry", "existing full HTTP Spring initializer; compiled test entry, not a packaged boot claim"));
        identity.put("javaPid", ProcessHandle.current().pid());
        Process git = new ProcessBuilder("git", "-C", P0AcceptanceSchema.repoRoot().toAbsolutePath().toString(),
                "rev-parse", "HEAD", "HEAD^{tree}").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(git.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, git.exitValue());
            String[] revision = new String(git.getInputStream().readNBytes(128), StandardCharsets.US_ASCII).trim().split("\\s+");
            assertEquals(2, revision.length); assertEquals(source, revision[0]); assertTrue(revision[1].matches("[0-9a-f]{40}"));
            identity.put("sourceTree", revision[1]);
        } finally { if (git.isAlive()) git.destroyForcibly(); git.getInputStream().close(); }
        try (var input = Files.newInputStream(jar)) { identity.put("jarSha256", streamSha(input)); }
        for (Class<?> type : List.of(getClass(), CombinedCoreHttpWorkload.class, CombinedControlledProviders.class,
                GameplayProcessRecoveryChild.class, CrossDomainStorageFixture.class, CrossDomainTransportStage.class,
                CrossDomainSchedulerStage.class, com.chengyinhub.business.service.impl.CrossDomainSubscriptionProvider.class))
            try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) { assertNotNull(input); actualClasses.put(type.getName(), streamSha(input)); }
        for (String name : List.of("com.chengyinhub.business.it.CombinedControlledProviders$1",
                "com.chengyinhub.business.it.CombinedControlledProviders$Mode",
                "com.chengyinhub.business.it.CombinedCoreHttpWorkload$HttpStatusFailure",
                "com.chengyinhub.business.it.CrossDomainCombinedLoadMySqlIT$Initializer")) {
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            try (var input = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                assertNotNull(input); actualClasses.put(name, streamSha(input));
            }
        }
        identity.put("actualClassSha256", actualClasses); return identity;
    }
    private static String streamSha(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(input, hash)) { stream.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(hash.digest());
    }
    private String actorDigest(JsonNode identities) {
        return digest(jdbc, "SELECT * FROM ums_member WHERE id IN (?,?) ORDER BY id",
                identities.path("a").path("memberId").asLong(), identities.path("b").path("memberId").asLong());
    }
    private static String economicDigest(CrossDomainStorageFixture storage) {
        return CombinedCoreHttpWorkload.sha((digest(storage.db, "SELECT * FROM oms_order ORDER BY id")
                + digest(storage.db, "SELECT * FROM merchant_subscription ORDER BY id")
                + digest(storage.db, "SELECT * FROM wx_verified_payment_fact ORDER BY id")).getBytes(StandardCharsets.UTF_8));
    }
    private static String unknownDigest(CrossDomainStorageFixture storage) {
        return CombinedCoreHttpWorkload.sha((digest(storage.db, "SELECT id,member_id,session_id,action_key,request_fingerprint,nonce,reservation,original_day,provider_state FROM photo_check_intent WHERE session_id BETWEEN 102 AND 105 ORDER BY id")
                + digest(storage.db, "SELECT * FROM ai_quota_reservation WHERE nonce IN (SELECT nonce FROM photo_check_intent WHERE session_id BETWEEN 102 AND 105) ORDER BY id"))
                .getBytes(StandardCharsets.UTF_8));
    }
    private static String retainedDigest(CrossDomainStorageFixture storage, long id) {
        assertEquals("UNKNOWN", storage.db.queryForObject("SELECT provider_state FROM photo_check_intent WHERE id=?", String.class, id));
        assertEquals("RESERVED", storage.db.queryForObject("SELECT state FROM ai_quota_reservation WHERE nonce=(SELECT nonce FROM photo_check_intent WHERE id=?)", String.class, id));
        return CombinedCoreHttpWorkload.sha((digest(storage.db, "SELECT * FROM photo_check_intent WHERE id=?", id)
                + digest(storage.db, "SELECT * FROM ai_quota_reservation WHERE nonce=(SELECT nonce FROM photo_check_intent WHERE id=?)", id))
                .getBytes(StandardCharsets.UTF_8));
    }
    private static void assertRetained(CrossDomainStorageFixture storage, Map<Long,String> originals) {
        originals.forEach((id, hash) -> assertEquals(hash, retainedDigest(storage, id), "UNKNOWN must keep original identity, body and reservation"));
    }
    private static String digest(JdbcTemplate source, String query, Object... args) {
        try {
            return CombinedCoreHttpWorkload.sha(FACT_JSON.writeValueAsBytes(completeFactRows(source.queryForList(query, args))));
        } catch (Exception failure) {
            throw new IllegalStateException("CONTROLLED_FACT_OBSERVATION_FAILED", failure);
        }
    }
    /** The original SQL-row representation, shared by the core workload's complete fact groups. */
    private static List<Map<String,Object>> completeFactRows(List<Map<String,Object>> rows) {
        List<Map<String,Object>> facts = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            Map<String,Object> complete = new TreeMap<>();
            for (var column : row.entrySet()) {
                Object value = column.getValue();
                // Jackson's legacy Date serializer keeps only milliseconds. Preserve SQL
                // Timestamp nanos, and all Date/Time milliseconds, without timezone formatting.
                if (value instanceof java.sql.Timestamp timestamp)
                    value = List.of("java.sql.Timestamp", timestamp.toInstant().getEpochSecond(), timestamp.getNanos());
                else if (value instanceof java.sql.Date date)
                    value = List.of("java.sql.Date", date.getTime());
                else if (value instanceof java.sql.Time time)
                    value = List.of("java.sql.Time", time.getTime());
                complete.put(column.getKey(), value);
            }
            facts.add(complete); // Keep the original SQL row order, every column and every null.
        }
        return facts;
    }
    static byte[] completeFactGroups(Map<String,List<Map<String,Object>>> groups) throws IOException {
        Map<String,Object> facts = new TreeMap<>();
        groups.forEach((name, rows) -> facts.put(name, completeFactRows(rows)));
        return FACT_JSON.writeValueAsBytes(facts);
    }
    private static String resourceFile(String name) {
        try { return Files.readString(Path.of("/sys/fs/cgroup", name)).trim(); }
        catch (Exception unavailable) { return "UNKNOWN"; }
    }
    private static Map<String,Object> sample(String phase, DruidDataSource app, DruidDataSource fixture,
                                             JdbcTemplate observer, String appSchema, String economicSchema) {
        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var http = (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(
                com.chengyinhub.common.utils.http.HttpUtils.class, "HTTP_EXECUTOR");
        var cleanup = (ThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(
                com.chengyinhub.common.utils.http.HttpUtils.class, "DISCONNECT_EXECUTOR");
        assertNotNull(http); assertNotNull(cleanup);
        Map<String,Object> sample = new LinkedHashMap<>(); sample.put("phase", phase);
        sample.put("monotonicNanos", System.nanoTime()); sample.put("heapUsedBytes", heap.getUsed());
        sample.put("heapMaxBytes", heap.getMax());
        var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuTime = os instanceof com.sun.management.OperatingSystemMXBean cpu ? cpu.getProcessCpuTime() : -1L;
        sample.put("processCpuNanos", cpuTime >= 0 ? cpuTime : "UNKNOWN");
        sample.put("applicationActiveConnections", app.getActiveCount()); sample.put("applicationIdleConnections", app.getPoolingCount());
        sample.put("fixtureActiveConnections", fixture.getActiveCount()); sample.put("fixtureIdleConnections", fixture.getPoolingCount());
        sample.put("httpActive", http.getActiveCount()); sample.put("httpQueued", http.getQueue().size());
        sample.put("cleanupActive", cleanup.getActiveCount()); sample.put("cleanupQueued", cleanup.getQueue().size());
        sample.put("physicalConnectionsInTwoSchemas", observer.queryForObject(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE DB IN (?,?)", Integer.class, appSchema, economicSchema));
        sample.put("source", "JVM MXBeans; original Druid counters; original HttpUtils executor counters"); return sample;
    }
}
