package com.chengyinhub.business.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.discovery.EggDiscoveryEvaluator.*;
import static com.chengyinhub.business.discovery.EggDiscoveryRows.*;

/**
 * Durable ISOLATED_EVALUATION only. Not a Spring bean or HTTP endpoint.
 * Reuses the existing local TransactionTemplate / MyBatis current-read / transactional-outbox pattern.
 * Dedicated evaluation tables never feed business audit/reward consumers. No production Source is supplied.
 */
public final class EggDiscoveryTransactionalService {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private static final String LEVEL = "ISOLATED_EVALUATION";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Mode mode;
    private final EggDiscoveryMapper mapper;
    private final TransactionTemplate transaction;
    private final Source source;
    private final Clock clock;

    public EggDiscoveryTransactionalService() {
        mode = Mode.DISABLED; mapper = null; transaction = null; source = null; clock = null;
    }
    public EggDiscoveryTransactionalService(Mode mode, EggDiscoveryMapper mapper, PlatformTransactionManager manager,
                                             Source source, Clock clock) {
        require(mode != null && mapper != null && manager != null && source != null && clock != null, "DEPENDENCIES_REQUIRED");
        this.mode = mode; this.mapper = mapper; this.transaction = new TransactionTemplate(manager);
        this.source = source; this.clock = clock;
    }
    private interface Work<T> { T run(); }
    private <T> T atomic(Work<T> work) {
        require(mode == Mode.ISOLATED_PERSISTENCE_EVALUATION, "DISCOVERY_PERSISTENCE_DISABLED");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "NESTED_TRANSACTION_FORBIDDEN");
        return transaction.execute(status -> work.run());
    }

    /** Explicit scratch setup; does not approve a rule, reactivate a revoked scope or install a live definition. */
    public void openEvaluation(final long memberId, final Rule rule) {
        atomic(() -> {
            require(memberId > 0 && rule != null, "OWNER_RULE_REQUIRED");
            ScopeRow row = row(memberId, rule);
            mapper.insertScope(row);
            ScopeRow current = mapper.lockScope(row.evaluationKey, memberId);
            requireBinding(current, memberId, rule.scope, rule.id);
            requireRule(current, rule);
            require("ACTIVE".equals(current.status), "EVALUATION_REVOKED");
            return null;
        });
    }

    /** Locks owner-scoped evaluation before source read; no caller-supplied event/rule/receipt is persisted. */
    public EvaluationReceipt discover(final long memberId, final Scope scope, final String definitionId) {
        return atomic(() -> {
            String key = evaluationKey(memberId, scope, definitionId);
            ScopeRow guard = mapper.lockScope(key, memberId);
            requireBinding(guard, memberId, scope, definitionId);
            require("ACTIVE".equals(guard.status), "EVALUATION_REVOKED");
            final Snapshot[] captured = new Snapshot[1];
            final Instant now = clock.instant();
            Source currentSource = (m, s, d, n) -> {
                Snapshot current = source.readCurrent(m, s, d, n);
                require(current != null, "CURRENT_AUTHORITY_REQUIRED");
                requireRule(guard, current.rule);
                captured[0] = current;
                return current;
            };
            Receipt evaluated = new EggDiscoveryEvaluator(EggDiscoveryEvaluator.Mode.ISOLATED_EVALUATION,
                    currentSource, Clock.fixed(now, ZoneOffset.UTC)).discover(memberId, scope, definitionId);
            // Current locking read: prior transaction may have committed while this request waited on guard.
            ReceiptRow stored = mapper.lockReceipt(key, memberId);
            if (evaluated == null) {
                require(stored == null, "SOURCE_HISTORY_CHANGED");
                return null;
            }
            require(LEVEL.equals(evaluated.provenance), "EVIDENCE_LEVEL_MISMATCH");
            Map<String, String> hashes = historyHashes(captured[0]);
            if (stored != null) {
                require(stored.memberId == memberId && key.equals(stored.evaluationKey)
                        && LEVEL.equals(stored.evidenceLevel) && evaluated.receiptId.equals(stored.receiptId)
                        && evaluated.definitionFingerprint.equals(stored.definitionFingerprint)
                        && evaluated.discoveredAt.toString().equals(stored.discoveredAt), "RECEIPT_BINDING_CONFLICT");
                Map<String, String> original = readHashes(stored.historyHashes);
                require(hashes.entrySet().containsAll(original.entrySet()), "SOURCE_HISTORY_CHANGED");
                return new EvaluationReceipt(stored, true);
            }
            ReceiptRow row = new ReceiptRow(); row.evaluationKey = key; row.memberId = memberId;
            row.receiptId = evaluated.receiptId; row.definitionFingerprint = evaluated.definitionFingerprint;
            row.discoveredAt = evaluated.discoveredAt.toString(); row.evidenceLevel = LEVEL;
            row.historyHashes = writeHashes(hashes);
            changed(mapper.insertReceipt(row));
            outbox(guard, "EVALUATED_STORY_DISCOVERY", now);
            return new EvaluationReceipt(row, false);
        });
    }

    /** One-way local evaluation revocation. Retains historical receipt and emits one evaluation-only event. */
    public void revokeEvaluation(final long memberId, final Scope scope, final String definitionId) {
        atomic(() -> {
            String key = evaluationKey(memberId, scope, definitionId);
            ScopeRow guard = mapper.lockScope(key, memberId);
            requireBinding(guard, memberId, scope, definitionId);
            if ("REVOKED".equals(guard.status)) return null;
            require("ACTIVE".equals(guard.status), "EVALUATION_STATE_INVALID");
            changed(mapper.revoke(key, memberId, guard.revision));
            guard.revision++;
            outbox(guard, "EVALUATION_REVOKED", clock.instant());
            return null;
        });
    }
    private void outbox(ScopeRow guard, String kind, Instant now) {
        OutboxRow row = new OutboxRow(); row.eventId = hash(Arrays.asList(guard.evaluationKey, kind));
        row.evaluationKey = guard.evaluationKey; row.revision = guard.revision; row.eventType = kind;
        row.recordedAt = now.toString(); row.evidenceLevel = LEVEL;
        changed(mapper.insertOutbox(row));
    }
    private static void requireBinding(ScopeRow row, long member, Scope scope, String definitionId) {
        require(row != null && row.memberId == member && row.evaluationKey.equals(evaluationKey(member, scope, definitionId))
                && row.scopeKind.equals(scope.kind.name()) && row.ownerId.equals(scope.ownerId)
                && row.releaseId.equals(scope.releaseId) && row.occurrenceId.equals(scope.occurrenceId)
                && row.definitionId.equals(definitionId) && LEVEL.equals(row.evidenceLevel), "EVALUATION_SCOPE_MISMATCH");
    }
    private static void requireRule(ScopeRow row, Rule rule) {
        require(rule != null && row.definitionVersion == rule.version && row.definitionId.equals(rule.id)
                && row.definitionFingerprint.equals(rule.fingerprint), "IMMUTABLE_RULE_CONFLICT");
    }
    private static ScopeRow row(long member, Rule rule) {
        ScopeRow row = new ScopeRow(); row.evaluationKey = evaluationKey(member, rule.scope, rule.id);
        row.memberId = member; row.scopeKind = rule.scope.kind.name(); row.ownerId = rule.scope.ownerId;
        row.releaseId = rule.scope.releaseId; row.occurrenceId = rule.scope.occurrenceId;
        row.definitionId = rule.id; row.definitionVersion = rule.version; row.definitionFingerprint = rule.fingerprint;
        row.evidenceLevel = LEVEL; row.status = "ACTIVE"; row.revision = 1; return row;
    }
    private static String evaluationKey(long member, Scope scope, String definitionId) {
        require(member > 0 && scope != null && definitionId != null
                && definitionId.matches("[A-Za-z0-9._-]{1,128}"), "OWNER_SCOPE_REQUIRED");
        return hash(Arrays.asList(String.valueOf(member), scope.kind.name(), scope.ownerId,
                scope.releaseId, scope.occurrenceId, definitionId));
    }
    private static Map<String, String> historyHashes(Snapshot snapshot) {
        Map<String, String> hashes = new TreeMap<String, String>();
        for (Event event : snapshot.events) {
            String id = hash(Collections.singletonList(event.receiptId));
            String value = hash(Arrays.asList(event.receiptId, String.valueOf(event.memberId), event.scope.kind.name(),
                    event.scope.ownerId, event.scope.releaseId, event.scope.occurrenceId, String.valueOf(event.sequence),
                    event.stepId, event.occurredAt.toString(), String.valueOf(event.verified)));
            hashes.put(id, value); // evaluator already rejected conflicting duplicates before this point
        }
        return hashes;
    }
    private static String writeHashes(Map<String, String> hashes) {
        try { return JSON.writeValueAsString(hashes); }
        catch (Exception failure) { throw new IllegalStateException("EVIDENCE_ENCODING_FAILED"); }
    }
    private static Map<String, String> readHashes(String value) {
        try {
            Map<String, String> result = JSON.readValue(value, new TypeReference<Map<String, String>>() { });
            require(result != null && !result.isEmpty() && result.size() <= 10000, "INVALID_EVIDENCE_SNAPSHOT");
            for (Map.Entry<String, String> entry : result.entrySet())
                require(entry.getKey().matches("[0-9a-f]{64}") && entry.getValue() != null
                        && entry.getValue().matches("[0-9a-f]{64}"), "INVALID_EVIDENCE_SNAPSHOT");
            return result;
        } catch (Exception failure) { throw new IllegalStateException("INVALID_EVIDENCE_SNAPSHOT"); }
    }
    public static final class EvaluationReceipt {
        public final String receiptId;
        public final String evidenceLevel = LEVEL;
        public final Instant discoveredAt;
        public final boolean replay;
        private EvaluationReceipt(ReceiptRow row, boolean replay) {
            this.receiptId = row.receiptId; this.discoveredAt = Instant.parse(row.discoveredAt); this.replay = replay;
        }
        public Map<String, String> publicView() {
            Map<String, String> result = new LinkedHashMap<String, String>();
            result.put("receiptId", receiptId); result.put("kind", "STORY_UNLOCK");
            result.put("discoveredAt", discoveredAt.toString()); result.put("evidenceLevel", evidenceLevel);
            return Collections.unmodifiableMap(result);
        }
    }
    private static void changed(int count) { require(count == 1, "EVALUATION_WRITE_CONFLICT"); }
    private static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
    private static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
