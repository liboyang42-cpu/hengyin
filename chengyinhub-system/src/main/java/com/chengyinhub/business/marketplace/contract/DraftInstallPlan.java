package com.chengyinhub.business.marketplace.contract;

import java.time.Instant;
import java.util.Map;

/**
 * Inactive, server-only validation result. NOT a persisted install or reusable authorization token.
 * Future transactional clone must revalidate authority and atomically reserve quota/idempotency.
 * No source content or answer payload is carried; public APIs must project a dedicated safe DTO.
 */
public final class DraftInstallPlan {
    private final long authenticatedMemberId;
    private final ModuleInstallRequest request;
    private final ModuleVersionSnapshot source;
    private final ModuleLicenseGrant grant;
    private final ModuleInstallAuthority.OwnedDraft target;
    private final Map<String, ModuleInstallAuthority.BindingApproval> bindings;
    private final long usageRevision;
    private final Instant checkedAt;

    DraftInstallPlan(long authenticatedMemberId, ModuleInstallRequest request, ModuleVersionSnapshot source, ModuleLicenseGrant grant,
                     ModuleInstallAuthority.OwnedDraft target,
                     Map<String, ModuleInstallAuthority.BindingApproval> bindings,
                     long usageRevision, Instant checkedAt) {
        this.authenticatedMemberId = authenticatedMemberId;
        this.request = request;
        this.source = source;
        this.grant = grant;
        this.target = target;
        this.bindings = ContractChecks.map(bindings, "bindings");
        this.usageRevision = usageRevision;
        this.checkedAt = checkedAt;
    }

    public ModuleInstallAuthority.OwnedDraft.State getResultState() { return ModuleInstallAuthority.OwnedDraft.State.DRAFT; }
    public boolean isExecutable() { return false; }
    public boolean isPublished() { return false; }
    public boolean grantsPlayerAdmission() { return false; }
    public long getAuthenticatedMemberId() { return authenticatedMemberId; }
    public ModuleInstallRequest getRequest() { return request; }
    public ModuleVersionSnapshot getSource() { return source; }
    public ModuleLicenseGrant getGrant() { return grant; }
    public ModuleInstallAuthority.OwnedDraft getTarget() { return target; }
    public Map<String, ModuleInstallAuthority.BindingApproval> getBindings() { return bindings; }
    public long getUsageRevision() { return usageRevision; }
    public Instant getCheckedAt() { return checkedAt; }
}
