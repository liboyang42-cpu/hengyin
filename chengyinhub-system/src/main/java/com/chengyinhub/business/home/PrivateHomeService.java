package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.business.home.PrivateHomeLocationPolicy.Snapshot;
import com.chengyinhub.business.mapper.PrivateHomeMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** W19 owner-only transaction boundary. This service has no public-map/NPC/telemetry dependencies. */
@Service
public class PrivateHomeService {
    private static final String ACTION = "RESTORE_TO_CAP";
    @Autowired private PrivateHomeMapper mapper;
    @Autowired private PrivateHomeAccess access;
    @Autowired private DataSource dataSource;
    @Autowired(required = false) private PrivateHomeAuthority authority;
    @Autowired(required = false) private PrivateHomeResources resources;
    @Autowired private PrivateHomeRetentionPolicy retention = new PrivateHomeRetentionPolicy();
    @Autowired private PrivateHomeLocationPolicy locationPolicy = new PrivateHomeLocationPolicy();
    private Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public HomeView getMyHome() {
        Actor actor = actor();
        HomeView view = new HomeView(mapper.findHome(actor.ownerId));
        checkActor(actor);
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public Mutation setMyHome(String requestId, long expectedVersion, String label, Point point) {
        id(requestId); version(expectedVersion); validatePoint(point); validateLabel(label);
        Actor actor = actor();
        Home home = lock(actor);
        Policy policy = policy(actor);
        Mutation replay = mutationReplay(actor, requestId, "SET", expectedVersion);
        if (replay != null) return replay;
        long now = clock.millis();
        String decision = home.version != expectedVersion ? "VERSION_CONFLICT" :
            home.version > 0 && now < add(home.changedAt, policy.changeCooldownMillis) ? "HOME_CHANGE_COOLDOWN" :
            !authority.placementAllowed(actor, point) ? "PLACE_NOT_ALLOWED" : "SAVED";
        if ("SAVED".equals(decision)) {
            home.version = Math.addExact(home.version, 1); home.label = label;
            home.latitude = point.latitude; home.longitude = point.longitude; home.datum = point.datum;
            home.status = "ACTIVE"; home.changedAt = now; home.effectiveAt = add(now, policy.activationDelayMillis);
            affected(mapper.writeHome(home)); mapper.revokeProofs(actor.ownerId);
        }
        Mutation result = mutation(actor, home, requestId, "SET", expectedVersion, decision, now);
        samePolicy(policy, policy(actor)); checkActor(actor);
        if ("SAVED".equals(decision) && !authority.placementAllowed(actor, point)) fail("AUTHORITY_CHANGED");
        return result;
    }

    /** Deletion does not depend on restore/safety configuration; it clears all current private coordinates. */
    @Transactional(rollbackFor = Exception.class)
    public Mutation deleteMyHome(String requestId, long expectedVersion) {
        id(requestId); version(expectedVersion);
        Actor actor = actor(); Home home = lock(actor);
        Mutation replay = mutationReplay(actor, requestId, "DELETE", expectedVersion);
        if (replay != null) return replay;
        long now = clock.millis();
        String decision = home.version != expectedVersion ? "VERSION_CONFLICT" : "DELETED";
        if ("DELETED".equals(decision)) {
            home.version = Math.addExact(home.version, 1); home.status = "DELETED";
            home.label = null; home.latitude = null; home.longitude = null; home.datum = null;
            home.changedAt = now; home.effectiveAt = 0L;
            affected(mapper.writeHome(home)); mapper.revokeProofs(actor.ownerId);
            mapper.clearLocationEvidence(actor.ownerId);
        }
        Mutation result = mutation(actor, home, requestId, "DELETE", expectedVersion, decision, now);
        checkActor(actor);
        return result;
    }

    /** Internal only. The evidence handle refers to a trusted verifier; no arrival flag or client coordinates. */
    @Transactional(rollbackFor = Exception.class)
    public Proof verifyReturn(String requestId, long expectedVersion, String scope, String evidenceHandle) {
        id(requestId); version(expectedVersion); token(scope); token(evidenceHandle);
        Actor actor = actor(); Home home = lock(actor); Policy policy = policy(actor);
        ResourcePolicy resource = resourcePolicy(actor, scope);
        lockResource(actor, resource); // A current authorized real account is required even to issue a proof.
        Proof replay = mapper.findProofRequest(actor.ownerId, requestId);
        if (replay != null) {
            if (replay.homeVersion != expectedVersion || !scope.equals(replay.scope)) fail("REQUEST_CONFLICT");
            checkActor(actor);
            return replay; // Original proof never becomes fresh again. Restore rechecks expiry/revocation/scope.
        }
        long now = clock.millis();
        if (!"ACTIVE".equals(home.status) || home.version != expectedVersion) fail("HOME_VERSION_INVALID");
        if (now < home.effectiveAt) fail("HOME_NOT_EFFECTIVE");
        Arrival grant = authority.verifyArrival(actor, home, resource, evidenceHandle);
        if (grant == null || grant.ownerId != actor.ownerId || grant.epoch != actor.epoch
                || grant.homeVersion != home.version || !scope.equals(grant.scope)
                || !resource.scopeRevision.equals(grant.scopeRevision) || !resource.ruleVersion.equals(grant.ruleVersion)
                || !ACTION.equals(grant.action) || grant.expiresAt <= now) fail("ARRIVAL_NOT_VERIFIED");
        token(grant.grantId);
        Proof proof = new Proof(); proof.proofId = UUID.randomUUID().toString(); proof.ownerId = actor.ownerId;
        proof.homeVersion = home.version; proof.epoch = actor.epoch; proof.requestId = requestId; proof.scope = scope;
        proof.scopeRevision = resource.scopeRevision; proof.ruleVersion = resource.ruleVersion;
        proof.policyVersion = policy.version; proof.grantId = grant.grantId; proof.action = ACTION;
        proof.grantFingerprint = sha256(grant.grantId);
        proof.resourceType = resource.resourceType;
        proof.status = "READY"; proof.issuedAt = now; proof.expiresAt = Math.min(grant.expiresAt, add(now, policy.proofTtlMillis));
        affected(mapper.insertProof(proof));
        recheck(actor, home, policy, resource, proof);
        return proof;
    }

    /**
     * Manual return check. Location failures throw and do not mint a proof.
     * A stored request with the same scope and home version returns the original proof.
     */
    @Transactional(rollbackFor = Exception.class)
    public Proof verifyReturnHome(String requestId, long expectedVersion, String scope, String evidenceHandle, Observation observation) {
        id(requestId); version(expectedVersion); token(scope); token(evidenceHandle);
        Actor actor = actor();
        Snapshot rules = locationRules();
        Home home = lock(actor);
        Proof prior = mapper.findProofRequest(actor.ownerId, requestId);
        if (prior != null) {
            if (prior.homeVersion != expectedVersion || !scope.equals(prior.scope)) fail("REQUEST_CONFLICT");
            checkActor(actor);
            return prior;
        }
        if (!"ACTIVE".equals(home.status) || home.version != expectedVersion) fail("HOME_VERSION_INVALID");
        String location = PrivateHomeLocationRules.classify(rules, home, observation, clock.millis());
        if (location != null) fail(location);
        Long last = mapper.lastProofIssued(actor.ownerId, scope);
        if (last != null && clock.millis() < add(last, rules.verifyCooldownMillis)) fail("HOME_VERIFY_COOLDOWN");
        Proof proof = verifyReturn(requestId, expectedVersion, scope, evidenceHandle);
        if ("READY".equals(proof.status) && mapper.countLocationEvidence(actor.ownerId, proof.proofId) == 0) {
            long expires = Math.min(proof.expiresAt, add(proof.issuedAt, rules.locationRetentionTtlMillis));
            affected(mapper.insertLocationEvidence(actor.ownerId, proof.proofId, expires));
        }
        return proof;
    }

    @Transactional(readOnly = true)
    public Proof readMyProof(String requestId) {
        id(requestId);
        Actor actor = actor();
        Proof proof = mapper.findProofRequest(actor.ownerId, requestId);
        if (proof == null) fail("HOME_RESULT_ABSENT");
        checkActor(actor);
        return proof;
    }

    @Transactional(readOnly = true)
    public Receipt readMyReceipt(String requestId) {
        id(requestId);
        Actor actor = actor();
        Receipt receipt = mapper.findReceipt(actor.ownerId, requestId);
        if (receipt == null) fail("HOME_RESULT_ABSENT");
        checkActor(actor);
        return receipt;
    }

    /** Internal only; proof consumption, account CAS and receipt/ledger are one local database transaction. */
    @Transactional(rollbackFor = Exception.class)
    public Receipt restore(String requestId, String proofId, String scope) {
        id(requestId); token(proofId); token(scope);
        Actor actor = actor(); Home home = lock(actor); Policy policy = policy(actor);
        ResourcePolicy resource = resourcePolicy(actor, scope);
        Resource account = lockResource(actor, resource);
        Receipt replay = mapper.findReceipt(actor.ownerId, requestId);
        if (replay != null) {
            if (!Objects.equals(replay.proofId, proofId) || !scope.equals(replay.scope)) fail("REQUEST_CONFLICT");
            sameResource(resource, resourcePolicy(actor, scope)); checkActor(actor);
            return replay; // Current permission is checked BEFORE returning any historical result.
        }
        long now = clock.millis();
        Proof proof = mapper.findProof(actor.ownerId, proofId);
        String decision = proofDecision(actor, home, policy, resource, proof, now);
        if (decision == null) {
            Long last = mapper.lastRestore(actor.ownerId, scope);
            if (last != null && now < add(last, resource.restoreCooldownMillis)) decision = "RESTORE_COOLDOWN";
        }
        Receipt receipt = new Receipt(); receipt.receiptId = UUID.randomUUID().toString(); receipt.ownerId = actor.ownerId;
        receipt.homeVersion = home.version; receipt.requestId = requestId; receipt.proofId = proofId; receipt.scope = scope;
        receipt.scopeRevision = resource.scopeRevision; receipt.ruleVersion = resource.ruleVersion; receipt.createdAt = now;
        if (decision == null) {
            affected(mapper.consumeProof(actor.ownerId, proofId, now));
            receipt.beforeValue = account.current; receipt.afterValue = account.cap; receipt.amount = account.cap - account.current;
            affected(resources.restoreToCap(actor, resource, account, receipt.receiptId));
            receipt.decision = "RESTORED";
        } else receipt.decision = decision;
        affected(mapper.insertReceipt(receipt));
        // A revocation/configuration change during the write rolls the WHOLE transaction back.
        samePolicy(policy, policy(actor)); sameResource(resource, resourcePolicy(actor, scope)); checkActor(actor);
        if ("RESTORED".equals(receipt.decision)) {
            if (!authority.grantCurrent(actor, home, proof)) fail("AUTHORITY_CHANGED");
            if (clock.millis() >= proof.expiresAt) fail("HOME_PROOF_EXPIRED");
        }
        return receipt;
    }

    /**
     * Internal owner-scoped maintenance only; no scheduler or HTTP route. Clears only expired
     * READY grant references using existing expiry semantics. Never deletes home/proof/mutation/
     * receipt rows or fingerprints, so replay, cooldown and version-tombstone evidence survives.
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanupMyExpiredReferences() {
        PrivateHomeRetentionPolicy.Snapshot approved = retention.requireEnabled();
        long cutoff = approved.cutoff(clock.millis());
        Actor actor = actor();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) fail("HOME_TRANSACTION_REQUIRED");
        // Same owner lock/order as delete, verify and restore. Do not call lock(): its lazy expiry
        // intentionally uses now rather than this separately approved background grace cutoff.
        Home home = mapper.lockHome(actor.ownerId);
        int expired = 0;
        if (home != null) {
            expired = mapper.expireProofs(actor.ownerId, cutoff);
            mapper.expireLocationEvidence(actor.ownerId, clock.millis());
        }
        retention.revalidate(approved); checkActor(actor);
        return expired;
    }

    private String proofDecision(Actor actor, Home home, Policy policy, ResourcePolicy resource, Proof proof, long now) {
        if (proof == null) return "PROOF_INVALID";
        if (!"READY".equals(proof.status)) return "CONSUMED".equals(proof.status) ? "PROOF_CONSUMED" : "EXPIRED".equals(proof.status) ? "PROOF_EXPIRED" : "PROOF_REVOKED";
        if (!"ACTIVE".equals(home.status) || !home.version.equals(proof.homeVersion)) return "HOME_VERSION_INVALID";
        if (now < home.effectiveAt) return "HOME_NOT_EFFECTIVE";
        if (proof.expiresAt <= now) return "PROOF_EXPIRED";
        if (proof.epoch != actor.epoch || !ACTION.equals(proof.action) || !resource.scope.equals(proof.scope)
                || !resource.resourceType.equals(proof.resourceType)
                || !resource.scopeRevision.equals(proof.scopeRevision) || !resource.ruleVersion.equals(proof.ruleVersion)
                || !policy.version.equals(proof.policyVersion)) return "PROOF_SCOPE_CHANGED";
        if (!authority.grantCurrent(actor, home, proof)) return "PROOF_REVOKED";
        return null;
    }

    private Actor actor() {
        Actor actor = access.current();
        if (actor == null || actor.ownerId <= 0 || actor.epoch < 0) fail("HOME_UNAUTHORIZED");
        checkActor(actor); return actor;
    }
    private void checkActor(Actor actor) {
        access.revalidate(actor);
        if (authority == null) fail("HOME_AUTHORITY_UNAVAILABLE");
        authority.requireNative(actor);
    }
    private Home lock(Actor actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) fail("HOME_TRANSACTION_REQUIRED");
        mapper.ensureOwner(actor.ownerId);
        Home home = mapper.lockHome(actor.ownerId);
        if (home == null) fail("HOME_UNAVAILABLE");
        mapper.expireProofs(actor.ownerId, clock.millis());
        checkActor(actor); return home;
    }
    private Snapshot locationRules() {
        if (locationPolicy == null) fail("HOME_RULES_UNAVAILABLE");
        return locationPolicy.requireComplete();
    }
    private Policy policy(Actor actor) {
        Policy value = authority.policy(actor);
        if (value == null || !validToken(value.version) || !nonnegative(value.changeCooldownMillis)
                || !nonnegative(value.activationDelayMillis) || value.proofTtlMillis == null || value.proofTtlMillis <= 0)
            fail("HOME_RULES_UNAVAILABLE");
        Policy snapshot = new Policy(); snapshot.version=value.version;
        snapshot.changeCooldownMillis=value.changeCooldownMillis; snapshot.activationDelayMillis=value.activationDelayMillis;
        snapshot.proofTtlMillis=value.proofTtlMillis; return snapshot;
    }
    private ResourcePolicy resourcePolicy(Actor actor, String scope) {
        ResourcePolicy value = authority.resourcePolicy(actor, scope);
        if (value == null || !scope.equals(value.scope) || !validToken(value.scopeRevision) || !validToken(value.ruleVersion)
                || !("STAMINA".equals(value.resourceType) || "ENERGY".equals(value.resourceType))
                || !nonnegative(value.restoreCooldownMillis)) fail("RESOURCE_NOT_AUTHORIZED");
        ResourcePolicy snapshot = new ResourcePolicy(); snapshot.scope=value.scope; snapshot.resourceType=value.resourceType;
        snapshot.scopeRevision=value.scopeRevision; snapshot.ruleVersion=value.ruleVersion;
        snapshot.restoreCooldownMillis=value.restoreCooldownMillis; return snapshot;
    }
    private Resource lockResource(Actor actor, ResourcePolicy policy) {
        if (resources == null || resources.transactionDataSource() != dataSource) fail("RESOURCE_ADAPTER_UNAVAILABLE");
        Resource value = resources.lockAuthorized(actor, policy);
        if (value == null || value.version < 0 || value.cap <= 0 || value.current < 0 || value.current > value.cap)
            fail("RESOURCE_ACCOUNT_UNAVAILABLE");
        return value;
    }
    private void recheck(Actor actor, Home home, Policy policy, ResourcePolicy resource, Proof proof) {
        samePolicy(policy, policy(actor)); sameResource(resource, resourcePolicy(actor, resource.scope)); checkActor(actor);
        if (!authority.grantCurrent(actor, home, proof)) fail("AUTHORITY_CHANGED");
        if (clock.millis() >= proof.expiresAt) fail("HOME_PROOF_EXPIRED");
    }
    private static void samePolicy(Policy a, Policy b) {
        if (!Objects.equals(a.version,b.version) || !Objects.equals(a.changeCooldownMillis,b.changeCooldownMillis)
                || !Objects.equals(a.activationDelayMillis,b.activationDelayMillis) || !Objects.equals(a.proofTtlMillis,b.proofTtlMillis)) fail("AUTHORITY_CHANGED");
    }
    private static void sameResource(ResourcePolicy a, ResourcePolicy b) {
        if (!Objects.equals(a.scope,b.scope) || !Objects.equals(a.scopeRevision,b.scopeRevision)
                || !Objects.equals(a.ruleVersion,b.ruleVersion) || !Objects.equals(a.resourceType,b.resourceType)
                || !Objects.equals(a.restoreCooldownMillis,b.restoreCooldownMillis) || !Objects.equals(a.cap,b.cap)) fail("AUTHORITY_CHANGED");
    }
    private Mutation mutationReplay(Actor actor, String id, String op, long expected) {
        Mutation prior = mapper.findMutation(actor.ownerId,id);
        if (prior != null) {
            if (!op.equals(prior.operation) || prior.expectedVersion != expected) fail("REQUEST_CONFLICT");
            checkActor(actor);
        }
        return prior;
    }
    private Mutation mutation(Actor actor, Home home, String id, String op, long expected, String decision, long now) {
        Mutation value = new Mutation(); value.ownerId=actor.ownerId; value.requestId=id; value.operation=op;
        value.expectedVersion=expected; value.resultVersion=home.version; value.decision=decision; value.createdAt=now;
        affected(mapper.insertMutation(value)); return value;
    }
    private static void validatePoint(Point point) {
        if (point == null || !"WGS84".equals(point.datum) || point.latitude == null || point.longitude == null
                || point.latitude.scale() > 6 || point.longitude.scale() > 6
                || point.latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || point.longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) fail("HOME_POINT_INVALID");
    }
    private static void validateLabel(String label) {
        if (label == null || label.trim().isEmpty() || label.length() > 40) fail("HOME_LABEL_INVALID");
        for (int i=0;i<label.length();i++) if (Character.isISOControl(label.charAt(i)) || Character.isSurrogate(label.charAt(i))) fail("HOME_LABEL_INVALID");
    }
    private static boolean nonnegative(Long n) { return n != null && n >= 0; }
    private static long add(long a, long b) { try { return Math.addExact(a,b); } catch (ArithmeticException e) { fail("HOME_RULES_UNAVAILABLE"); return 0; } }
    private static void version(long version) { if (version < 0 || version == Long.MAX_VALUE) fail("HOME_VERSION_INVALID"); }
    private static boolean validToken(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,128}"); }
    private static void token(String value) { if (!validToken(value)) fail("HOME_REQUEST_INVALID"); }
    private static void id(String value) { token(value); if (value.length()>64) fail("HOME_REQUEST_INVALID"); }
    private static void affected(int rows) { if (rows != 1) fail("HOME_WRITE_CONFLICT"); }
    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { fail("HOME_UNAVAILABLE"); return ""; }
    }
    private static void fail(String code) { throw new ServiceException(code); }
}
