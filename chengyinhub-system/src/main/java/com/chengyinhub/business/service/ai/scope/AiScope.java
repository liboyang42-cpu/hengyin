package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.Objects;

/**
 * Inactive server-only scope seam. Construction validates shape, NOT authentication/authorization.
 * A future trusted adapter must revalidate grants/run membership on every request before constructing it.
 * No JSON binding, bean, mapper, model client or runtime endpoint depends on this class.
 */
public final class AiScope
{
    public enum ActorType { PLAYER, TEAM }
    public enum AccessStatus { ACTIVE, REVOKED }

    /** Values must come from the authority/consent services, never prompt text or client claims. */
    public static final class Authority
    {
        private final long principalId;
        private final ActorType actorType;
        private final long actorId;
        private final String accessDecisionId;
        private final String policyVersion;
        private final String consentVersion;
        private final long dataEpoch;
        private final Instant validUntil;
        private final AccessStatus accessStatus;

        public Authority(long principalId, ActorType actorType, long actorId, String accessDecisionId,
                         String policyVersion, String consentVersion, long dataEpoch, Instant validUntil, AccessStatus accessStatus)
        {
            this.principalId = AiContractSupport.positive(principalId, "principalId");
            this.actorType = Objects.requireNonNull(actorType, "actorType");
            this.actorId = AiContractSupport.positive(actorId, "actorId");
            this.accessDecisionId = AiContractSupport.text(accessDecisionId, "accessDecisionId");
            this.policyVersion = AiContractSupport.text(policyVersion, "policyVersion");
            this.consentVersion = AiContractSupport.text(consentVersion, "consentVersion");
            if (dataEpoch < 0) throw new IllegalArgumentException("dataEpoch must not be negative");
            this.dataEpoch = dataEpoch;
            this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
            this.accessStatus = Objects.requireNonNull(accessStatus, "accessStatus");
        }

        String persistentFingerprint()
        {
            return AiContractSupport.fingerprint(principalId, actorType, actorId,
                    policyVersion, consentVersion, dataEpoch);
        }

        String fingerprint()
        {
            return AiContractSupport.fingerprint(principalId, actorType, actorId, accessDecisionId,
                    policyVersion, consentVersion, dataEpoch, validUntil, accessStatus);
        }
    }

    private final AiScenario scenario;
    private final Authority authority;
    private final Long workspaceId;
    private final Long merchantId;
    private final AiReleaseReference release;
    private final Long runId;
    private final Long bindingId;
    private final String fingerprint;
    private final String persistentFingerprint;

    private AiScope(AiScenario scenario, Authority authority, Long workspaceId, Long merchantId,
                    AiReleaseReference release, Long runId, Long bindingId)
    {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.workspaceId = workspaceId;
        this.merchantId = merchantId;
        this.release = release;
        this.runId = runId;
        this.bindingId = bindingId;
        this.persistentFingerprint = AiContractSupport.fingerprint("ai-memory-scope-v1", scenario, authority.persistentFingerprint(),
                workspaceId, merchantId, release == null ? null : release.fingerprint(), runId, bindingId);
        this.fingerprint = AiContractSupport.fingerprint("ai-scope-v1", scenario, authority.fingerprint(),
                workspaceId, merchantId, release == null ? null : release.fingerprint(), runId, bindingId);
    }

    public static AiScope assistant(AiScenario scenario, Authority authority, long workspaceId)
    {
        if (scenario != AiScenario.PLATFORM_ASSISTANT && scenario != AiScenario.CREATOR_ASSISTANT)
        {
            throw new IllegalArgumentException("workspace scope requires platform or creator scenario");
        }
        return new AiScope(scenario, authority, AiContractSupport.positive(workspaceId, "workspaceId"),
                null, null, null, null);
    }

    public static AiScope merchant(AiScenario scenario, Authority authority, long merchantId)
    {
        if (scenario != AiScenario.MERCHANT_PRIVATE_ASSISTANT && scenario != AiScenario.STORE_PUBLIC_NPC)
        {
            throw new IllegalArgumentException("merchant scope requires private assistant or public store NPC");
        }
        return new AiScope(scenario, authority, null, AiContractSupport.positive(merchantId, "merchantId"),
                null, null, null);
    }

    public static AiScope theme(AiScenario scenario, Authority authority, AiReleaseReference release,
                                long runId, long bindingId, Long merchantId)
    {
        if (scenario == null || !scenario.isTheme()) throw new IllegalArgumentException("theme scenario required");
        Objects.requireNonNull(release, "release").requireFrozenReviewed();
        if (merchantId != null) AiContractSupport.positive(merchantId, "merchantId");
        return new AiScope(scenario, authority, null, merchantId, release,
                AiContractSupport.positive(runId, "runId"), AiContractSupport.positive(bindingId, "bindingId"));
    }

    public void requireActiveAt(Instant now)
    {
        if (!isActiveAt(now))
        {
            throw new IllegalArgumentException("scope authority is revoked or expired");
        }
    }

    boolean isActiveAt(Instant now)
    {
        return authority.accessStatus == AccessStatus.ACTIVE
                && Objects.requireNonNull(now, "now").isBefore(authority.validUntil);
    }

    /** Stable identity is NOT current permission; callers must still requireActiveAt on every read. */
    public boolean samePersistentScope(AiScope other)
    {
        return other != null && persistentFingerprint.equals(other.persistentFingerprint);
    }

    public String getPersistentFingerprint() { return persistentFingerprint; }

    public boolean sameScope(AiScope other)
    {
        return other != null && fingerprint.equals(other.fingerprint);
    }

    public AiScenario getScenario() { return scenario; }
    public Long getWorkspaceId() { return workspaceId; }
    public Long getMerchantId() { return merchantId; }
    public AiReleaseReference getRelease() { return release; }
    public Long getRunId() { return runId; }
    public Long getBindingId() { return bindingId; }
    public String getFingerprint() { return fingerprint; }
    public String getConsentVersion() { return authority.consentVersion; }
    public long getDataEpoch() { return authority.dataEpoch; }
    public Instant getValidUntil() { return authority.validUntil; }
}
