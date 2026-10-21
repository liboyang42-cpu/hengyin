package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Actor;
import com.chengyinhub.business.home.PrivateHomeContracts.Arrival;
import com.chengyinhub.business.home.PrivateHomeContracts.Home;
import com.chengyinhub.business.home.PrivateHomeContracts.Point;
import com.chengyinhub.business.home.PrivateHomeContracts.Policy;
import com.chengyinhub.business.home.PrivateHomeContracts.Proof;
import com.chengyinhub.business.home.PrivateHomeContracts.ResourcePolicy;
import com.chengyinhub.business.home.PrivateHomeLocationPolicy.Snapshot;
import com.chengyinhub.common.exception.ServiceException;
import java.util.UUID;

/**
 * Wirable authority. It grants nothing unless the versioned policy is complete and the
 * operator explicitly selects a known placement rule and manual grant mode. Defaults do neither.
 */
public final class PrivateHomeConfiguredAuthority implements PrivateHomeAuthority {
    static final String PLACEMENT = "OWNER_PIN_ONLY";
    static final String GRANT = "MANUAL_OWNER_FIX";
    private final PrivateHomeLocationPolicy rules;
    private final PrivateHomeTrustedEvidence evidence;

    public PrivateHomeConfiguredAuthority(PrivateHomeLocationPolicy rules, PrivateHomeTrustedEvidence evidence) {
        this.rules = rules;
        this.evidence = evidence;
    }

    @Override public void requireNative(Actor actor) {
        if (actor == null || actor.ownerId <= 0 || actor.epoch < 0) throw new ServiceException("HOME_UNAUTHORIZED");
        rules.requireComplete();
    }

    @Override public Policy policy(Actor actor) {
        Snapshot snapshot = rules.requireComplete();
        Policy policy = new Policy();
        policy.version = snapshot.version;
        policy.changeCooldownMillis = snapshot.changeCooldownMillis;
        policy.activationDelayMillis = snapshot.activationDelayMillis;
        policy.proofTtlMillis = snapshot.proofTtlMillis;
        return policy;
    }

    @Override public boolean placementAllowed(Actor actor, Point point) {
        try { rules.requireComplete(); }
        catch (ServiceException closed) { return false; }
        return PLACEMENT.equals(rules.getPlacementRuleVersion());
    }

    @Override public ResourcePolicy resourcePolicy(Actor actor, String scope) {
        Snapshot snapshot = rules.requireComplete();
        if (!snapshot.resourceScope.equals(scope)) throw new ServiceException("RESOURCE_NOT_AUTHORIZED");
        ResourcePolicy policy = new ResourcePolicy();
        policy.scope = scope;
        policy.resourceType = snapshot.resourceType;
        policy.scopeRevision = snapshot.version;
        policy.ruleVersion = snapshot.version;
        policy.restoreCooldownMillis = snapshot.restoreCooldownMillis;
        policy.cap = snapshot.resourceCap;
        return policy;
    }

    @Override public Arrival verifyArrival(Actor actor, Home home, ResourcePolicy resource, String evidenceHandle) {
        Snapshot snapshot;
        try { snapshot = rules.requireComplete(); }
        catch (ServiceException closed) { return null; }
        // A token-shaped handle is only a lookup key. It is not a verdict.
        if (!GRANT.equals(rules.getArrivalGrantMode()) || evidence == null || actor == null
                || evidenceHandle == null || !evidenceHandle.matches("[A-Za-z0-9._:-]{1,128}")
                || home == null || home.version == null || resource == null
                || !snapshot.resourceScope.equals(resource.scope)) return null;
        long now = System.currentTimeMillis();
        PrivateHomeTrustedEvidence.Verdict verdict = evidence.resolve(new PrivateHomeTrustedEvidence.Query(
                actor.ownerId, home.version, resource.scope, evidenceHandle, resource.ruleVersion, now));
        if (verdict == null || !evidenceHandle.equals(verdict.evidenceId) || verdict.ownerId != actor.ownerId
                || verdict.homeVersion != home.version || verdict.resourceScope == null
                || !verdict.resourceScope.equals(resource.scope) || verdict.ruleVersion == null
                || !verdict.ruleVersion.equals(resource.ruleVersion) || verdict.freshUntil <= now) return null;
        String bound = PrivateHomeTrustedEvidence.fingerprint(verdict.evidenceId);
        if (bound == null) return null;
        Arrival grant = new Arrival();
        grant.grantId = UUID.randomUUID().toString();
        grant.evidenceFingerprint = bound;
        grant.ownerId = verdict.ownerId;
        grant.epoch = actor.epoch;
        grant.homeVersion = verdict.homeVersion;
        grant.scope = verdict.resourceScope;
        grant.scopeRevision = resource.scopeRevision;
        grant.ruleVersion = verdict.ruleVersion;
        grant.action = "RESTORE_TO_CAP";
        grant.expiresAt = verdict.freshUntil;
        return grant;
    }

    @Override public boolean grantCurrent(Actor actor, Home home, Proof proof) {
        try {
            Snapshot snapshot = rules.requireComplete();
            return GRANT.equals(rules.getArrivalGrantMode()) && proof != null && snapshot.resourceScope.equals(proof.scope)
                    && snapshot.version.equals(proof.ruleVersion) && snapshot.version.equals(proof.policyVersion);
        } catch (ServiceException closed) { return false; }
    }
}
