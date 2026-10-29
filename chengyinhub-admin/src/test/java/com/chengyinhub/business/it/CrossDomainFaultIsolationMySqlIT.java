package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.quartz.util.CrossDomainSchedulerStage;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SOURCE ONLY, explicit -Dtest=CrossDomainFaultIsolationMySqlIT on the approved isolated runner.
 * No conditional skip. One actual failure window, one parent SQL pool/Redis, real callbacks/readback,
 * real process-global AI/map budgets and original scheduler probe. Providers are controlled test boundaries.
 * Notification progress is driven by the original common-HTTP timeout and same scheduled queue,
 * never by a parallel test-side dispatcher. No production T19/SLO claim before joint execution.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("t19-process-global-http-ai-map")
class CrossDomainFaultIsolationMySqlIT {
    private final List<Map<String,Object>> observations = new ArrayList<>();

    @Test @Timeout(150)
    void sameWindowRetainsOriginalEconomicIdentitiesAndRequiresUnpoisonedNotificationProgress() throws Throwable {
        try (var storage = new CrossDomainStorageFixture(); var transport = new CrossDomainTransportStage()) {
            storage.open(); transport.open();
            ExecutorService foreground = Executors.newFixedThreadPool(2);
            ExecutorService delivery = Executors.newSingleThreadExecutor();
            Future<?> poisonedDispatch = null;
            Throwable primary = null;
            try (var scheduler = new CrossDomainSchedulerStage(storage.pool, storage.schema, storage.original, storage.url, storage.user, storage.password)) {
                PhotoCheckIntentService left = storage.photo(transport.client(0), transport::image);
                PhotoCheckIntentService right = storage.photo(transport.client(1), transport::image);

                // Healthy control through the actual request/response parser and durable intent/quota path.
                PhotoCheckIntentCommand normal = storage.command(1);
                PhotoCheckIntentView prepared = storage.tx.execute(tx -> left.prepare(normal));
                assertNotNull(prepared);
                PhotoCheckIntentView received = left.executeOnce(CrossDomainStorageFixture.MEMBER, prepared.intentId(), normal.requestFingerprint());
                assertEquals(PhotoCheckIntentState.RESULT_READY, received.state()); assertEquals("RECEIVED", received.providerState());
                assertFalse(received.result().isDegraded());
                observations.add(Map.of("stage", "normal-photo", "outcome", "RECEIVED", "intentId", received.intentId()));
                storage.broadcast("inapp"); storage.notifications.runDueScheduled();
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM im_message", Integer.class));
                TopicRouteState initial = bounded(foreground, "normal-state", () -> storage.route.queryState(CrossDomainStorageFixture.MEMBER, 80L, null));
                assertEquals("ACTIVE", initial.getStatus()); assertEquals(7, initial.getVersion());
                String originalState = storage.db.queryForObject("SELECT state_json FROM topic_route_session", String.class);
                observations.add(storage.assertTransactionOracleDetectsParentTransaction());

                transport.beginFault();
                storage.poisonId = storage.broadcast("subscribe"); storage.healthyId = storage.broadcast("inapp");
                poisonedDispatch = delivery.submit(storage.notifications::runDueScheduled);
                boolean poisonObserved = storage.poisonEntered.await(5, TimeUnit.SECONDS);
                assertTrue(poisonObserved, poisonObserved ? "real subscribe poison entered"
                        : storage.notificationProgressDiagnostic(poisonedDispatch));
                long poisonReceipt = storage.db.queryForObject("SELECT id FROM official_broadcast_log WHERE broadcast_id=? AND channel='subscribe'", Long.class, storage.poisonId);
                assertEquals("UNKNOWN", storage.db.queryForObject("SELECT delivery_state FROM official_broadcast_log WHERE id=?", String.class, poisonReceipt));
                assertEquals(2, storage.db.queryForObject("SELECT COUNT(*) FROM official_broadcast WHERE status=1", Integer.class), "backlog remains visible under original broadcast identities");

                List<PhotoCheckIntentCommand> commands = new ArrayList<>(); List<PhotoCheckIntentView> originals = new ArrayList<>();
                List<Future<PhotoCheckIntentView>> calls = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    PhotoCheckIntentService service = i < 2 ? left : right;
                    PhotoCheckIntentCommand command = storage.command(i + 2); commands.add(command);
                    PhotoCheckIntentView intent = storage.tx.execute(tx -> service.prepare(command)); assertNotNull(intent); originals.add(intent);
                    calls.add(transport.callers.submit(() -> service.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint())));
                }
                assertTrue(transport.aiEntered.await(5, TimeUnit.SECONDS));
                for (Future<PhotoCheckIntentView> call : calls) {
                    PhotoCheckIntentView unknown = call.get(6, TimeUnit.SECONDS);
                    assertEquals(PhotoCheckIntentState.RESULT_READY, unknown.state()); assertEquals("UNKNOWN", unknown.providerState());
                    assertTrue(unknown.result().isDegraded(), "first real review stores its original local result independently of provider outcome");
                    observations.add(Map.of("stage", "held-photo", "outcome", "UNKNOWN", "providerStillHeld", transport.releaseAi.getCount() == 1, "intentId", unknown.intentId()));
                }
                transport.assertHeldAfterAiCallerTimeouts();
                List<Map<String,Object>> identities = storage.db.queryForList("SELECT id,member_id,session_id,action_key,request_fingerprint,nonce,reservation,original_day FROM photo_check_intent ORDER BY id");
                List<Map<String,Object>> quotaIdentities = storage.db.queryForList("SELECT member_id,biz_type,pool,original_day,counter_key,nonce,receipt_key FROM ai_quota_reservation ORDER BY id");

                // Real NOT_SENT busy branch reserves/refunds its own nonce only; it never frees older UNKNOWN.
                PhotoCheckIntentCommand busyCommand = storage.command(20);
                PhotoCheckIntentView busy = storage.tx.execute(tx -> left.prepare(busyCommand)); assertNotNull(busy);
                PhotoCheckIntentView busyResult = left.executeOnce(CrossDomainStorageFixture.MEMBER, busy.intentId(), busyCommand.requestFingerprint());
                assertEquals(PhotoCheckIntentState.RESULT_READY, busyResult.state()); assertEquals("NOT_SENT", busyResult.providerState());
                assertEquals(5, transport.aiRequests.get(), "busy original never reaches the local provider");
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM ai_quota_reservation WHERE state='REFUNDED'", Integer.class));
                assertEquals(4, storage.db.queryForObject("SELECT COUNT(*) FROM photo_check_intent WHERE provider_state='UNKNOWN'", Integer.class));
                observations.add(Map.of("stage", "busy-photo", "outcome", "NOT_SENT", "intentId", busyResult.intentId(), "originalUnknownCount", 4));

                // Payment observation was verified against the original attempt before load. Consumption is real here.
                assertEquals("SUCCESS", bounded(foreground, "payment-callback", () -> storage.callbacks.payment(storage.paymentPayload()).result()));
                bounded(foreground, "payment-readback", () -> { storage.assertPaymentReadback(); return true; });
                Map<String,String> wrongAmount = new HashMap<>(storage.paymentPayload()); wrongAmount.put("total", "1991");
                assertThrows(IllegalStateException.class, () -> storage.callbacks.payment(wrongAmount));
                storage.assertPaymentReadback();
                observations.add(Map.of("stage", "wrong-amount-callback", "outcome", "REJECTED", "grantCount", 1));
                for (int i = 0; i < 3; i++) {
                    TopicRouteState state = bounded(foreground, "loaded-state-" + i, () -> storage.route.queryState(CrossDomainStorageFixture.MEMBER, 80L, null));
                    assertEquals(initial.getSessionId(), state.getSessionId()); assertEquals(7, state.getVersion()); assertEquals("ACTIVE", state.getStatus());
                }
                bounded(foreground, "other-actor-refusal", () -> {
                    assertThrows(IllegalStateException.class, () -> storage.route.queryState(CrossDomainStorageFixture.MEMBER + 1, 80L, null)); return true;
                });
                assertEquals(originalState, storage.db.queryForObject("SELECT state_json FROM topic_route_session", String.class));
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM topic_route_session", Integer.class));
                assertEquals(0, storage.db.queryForObject("SELECT COUNT(*) FROM topic_route_action", Integer.class));
                var parentSql = storage.observeParentTransactions();
                observations.add(parentSql.evidence());
                assertEquals("CURRENT_PHYSICAL_WITNESS_IN_CACHE", parentSql.freshness().get("result"),
                        "held-window zero requires a fresh cache sample, never a stale or missing observation");
                assertEquals(0, parentSql.openTransactions(),
                        "held providers/notification may not keep a parent domain SQL transaction open: " + parentSql.evidence());
                assertEquals(Boolean.FALSE, parentSql.subscriptionRead().get("springTransactionActive"),
                        "the real notification member lookup must not inherit a parent transaction");
                if (Boolean.TRUE.equals(parentSql.subscriptionRead().get("parentPoolResourceBound"))) {
                    assertEquals(Boolean.TRUE, parentSql.subscriptionRead().get("jdbcAutoCommit"),
                            "a synchronization-bound notification read must remain auto-commit, never an open domain transaction");
                    assertEquals(Boolean.TRUE, parentSql.subscriptionRead().get("mysqlAutoCommit"),
                            "the actual notification MySQL session must agree with its JDBC auto-commit observation");
                }

                // The old leader is late while the original physical work is still in flight.
                scheduler.holdOriginalWorkAndLoseLeadership();
                assertEquals("SUCCESS", bounded(foreground, "handoff-callback-replay", () -> storage.callbacks.payment(storage.paymentPayload()).result()));
                storage.assertPaymentReadback(); transport.assertHeldAfterAiCallerTimeouts();
                scheduler.finishLateWorkerAndKillOnlyOwnedSuccessor();
                // The REAL WxSubscribeMsgServiceImpl + common HTTP deadline must let this SAME queue progress
                // even though the controlled provider has not returned. This is an observation bound, not an SLO.
                poisonedDispatch.get(15, TimeUnit.SECONDS);
                // Check original producer queue BEFORE releasing poison. No test-side parallel dispatch substitution.
                boolean healthyWhilePoisonHeld = storage.db.queryForObject("SELECT COUNT(*) FROM official_broadcast_log WHERE broadcast_id=? AND channel='inapp' AND delivery_state='SUCCESS'", Integer.class, storage.healthyId) == 1;
                observations.add(Map.of("stage", "notification-held", "healthyCommitted", healthyWhilePoisonHeld,
                        "poisonState", "UNKNOWN", "subscriptionAttempts", storage.subscriptionCalls.get()));

                // Observe original facts while faults remain; never interpret absence/timeout as an unsent permission.
                for (int i = 0; i < originals.size(); i++) {
                    var command = commands.get(i); var intent = originals.get(i); PhotoCheckIntentService service = i < 2 ? left : right;
                    PhotoCheckIntentView replay = service.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint());
                    assertEquals(intent.intentId(), replay.intentId()); assertEquals("UNKNOWN", replay.providerState());
                    assertEquals(intent.intentId(), service.read(CrossDomainStorageFixture.MEMBER, command.sessionId(), command.actionKey(), command.requestFingerprint()).intentId());
                }
                assertEquals(5, transport.aiRequests.get());
                assertEquals(identities, storage.db.queryForList("SELECT id,member_id,session_id,action_key,request_fingerprint,nonce,reservation,original_day FROM photo_check_intent WHERE id<? ORDER BY id", busy.intentId()));

                storage.releasePoison.countDown(); poisonedDispatch.get(8, TimeUnit.SECONDS);
                storage.notifications.runDueScheduled();
                assertEquals(1, storage.subscriptionCalls.get());
                assertEquals(poisonReceipt, storage.db.queryForObject("SELECT id FROM official_broadcast_log WHERE broadcast_id=? AND channel='subscribe'", Long.class, storage.poisonId));
                assertEquals("UNKNOWN", storage.db.queryForObject("SELECT delivery_state FROM official_broadcast_log WHERE id=?", String.class, poisonReceipt));
                assertEquals(1, storage.db.queryForObject("SELECT COUNT(*) FROM official_broadcast_log WHERE broadcast_id=? AND channel='inapp' AND delivery_state='SUCCESS'", Integer.class, storage.healthyId));
                assertEquals(2, storage.db.queryForObject("SELECT COUNT(*) FROM im_message", Integer.class));
                long recoveredSubscription = storage.broadcast("subscribe"); storage.notifications.runDueScheduled();
                assertEquals("SUCCESS", storage.db.queryForObject("SELECT delivery_state FROM official_broadcast_log WHERE broadcast_id=? AND channel='subscribe'", String.class, recoveredSubscription));
                assertEquals(2, storage.subscriptionCalls.get(), "recovery sends one explicit NEW broadcast, never replays original UNKNOWN");
                transport.finishFault();
                observations.add(Map.of("stage", "map-recovery", "expiredOriginalOutcome", "UNAVAILABLE", "explicitReadOutcome", "SUCCESS", "physicalPeak", transport.mapPeak.get()));
                for (int i = 0; i < originals.size(); i++) {
                    var command = commands.get(i); var intent = originals.get(i);
                    PhotoCheckIntentView replay = left.executeOnce(CrossDomainStorageFixture.MEMBER, intent.intentId(), command.requestFingerprint());
                    assertEquals(intent.intentId(), replay.intentId()); assertEquals("UNKNOWN", replay.providerState());
                }
                assertEquals(5, transport.aiRequests.get(), "provider recovery is not permission to retry the original UNKNOWN");
                // Controlled fixture clock expiry only AFTER physical exit; expiry never authorizes a provider resend.
                storage.db.update("UPDATE ai_quota_reservation SET next_attempt_at=DATE_SUB(NOW(),INTERVAL 1 SECOND),request_lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE member_id=? AND state='RESERVED'", CrossDomainStorageFixture.MEMBER);
                storage.recovery.runDue();
                assertEquals(4, storage.db.queryForObject("SELECT COUNT(*) FROM ai_quota_reservation WHERE state='UNCERTAIN'", Integer.class));
                storage.rememberQuotaKeys();
                String counter = storage.db.queryForObject("SELECT DISTINCT counter_key FROM ai_quota_reservation", String.class);
                assertEquals("5", storage.redis.opsForValue().get(counter), "one received + four original unknown; busy compensated once");
                observations.add(Map.of("stage", "quota-recovery", "unknownRetained", 4, "busyRefunded", 1, "chargedCounter", 5));
                assertEquals("SUCCESS", bounded(foreground, "recovered-callback-replay", () -> storage.callbacks.payment(storage.paymentPayload()).result()));
                storage.assertPaymentReadback();
                PhotoCheckIntentCommand fresh = storage.command(30); // separate explicit fixture action, never automatic recovery identity
                PhotoCheckIntentView freshIntent = storage.tx.execute(tx -> right.prepare(fresh)); assertNotNull(freshIntent);
                assertEquals("RECEIVED", right.executeOnce(CrossDomainStorageFixture.MEMBER, freshIntent.intentId(), fresh.requestFingerprint()).providerState());
                assertEquals(6, transport.aiRequests.get());
                assertEquals(identities, storage.db.queryForList("SELECT id,member_id,session_id,action_key,request_fingerprint,nonce,reservation,original_day FROM photo_check_intent WHERE id<? ORDER BY id", busy.intentId()));
                for (Map<String,Object> originalQuota : quotaIdentities)
                    assertEquals(originalQuota, storage.db.queryForMap("SELECT member_id,biz_type,pool,original_day,counter_key,nonce,receipt_key FROM ai_quota_reservation WHERE receipt_key=?", originalQuota.get("receipt_key")));
                assertEquals(originalState, storage.db.queryForObject("SELECT state_json FROM topic_route_session", String.class));
                storage.assertRouteAccessFixture();
                observations.add(Map.of("stage", "recovered", "schedulerEffects", scheduler.effects(), "schedulerEntries", scheduler.attempts(),
                        "providerPosts", transport.aiRequests.get(), "notificationUnknown", storage.subscriptionCalls.get()));
                System.out.println("T19_OBSERVATIONS " + com.alibaba.fastjson2.JSON.toJSONString(observations));
                assertTrue(healthyWhilePoisonHeld,
                        "T19 unfulfilled: the actual runDueScheduled queue must commit the later inapp intent before the earlier subscription poison is released; "
                        + "Do not relax this assertion or replace the producer with parallel fixture dispatch.");
            } catch (Throwable failure) {
                primary = failure;
                observations.add(storage.transactionSamplingDiagnostic());
                System.out.println("T19_PARTIAL_OBSERVATIONS " + com.alibaba.fastjson2.JSON.toJSONString(observations));
                throw failure;
            } finally {
                storage.releasePoison.countDown(); transport.releaseAi.countDown(); transport.releaseMap.countDown(); transport.releaseDisconnect.countDown(); transport.releaseCleanup.countDown();
                List<Executable> joins = new ArrayList<>();
                if (poisonedDispatch != null) { Future<?> task = poisonedDispatch; joins.add(() -> task.get(10, TimeUnit.SECONDS)); }
                foreground.shutdownNow(); delivery.shutdownNow();
                joins.add(() -> assertTrue(foreground.awaitTermination(10, TimeUnit.SECONDS)));
                joins.add(() -> assertTrue(delivery.awaitTermination(10, TimeUnit.SECONDS)));
                try { assertAll("owned business workers must exit", joins); }
                catch (Throwable cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
            }
        }
    }

    private <T> T bounded(ExecutorService workers, String stage, Callable<T> action) throws Exception {
        long start = System.nanoTime(); Future<T> result = workers.submit(action);
        try {
            T value = result.get(5, TimeUnit.SECONDS);
            observations.add(Map.of("stage", stage, "outcome", "RETURNED", "fixtureElapsedNanos", System.nanoTime() - start)); return value;
        } catch (Exception failure) {
            observations.add(Map.of("stage", stage, "outcome", failure.getClass().getSimpleName(), "fixtureElapsedNanos", System.nanoTime() - start));
            throw failure; // do not reinterpret timeout/NOT_FOUND as successful mutation, cancel, or retry.
        }
    }
}
