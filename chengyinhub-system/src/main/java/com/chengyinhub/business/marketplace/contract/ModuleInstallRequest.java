package com.chengyinhub.business.marketplace.contract;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Untrusted install intent, never proof of purchase, membership, resource ownership or review. */
public final class ModuleInstallRequest {
    public enum Change { BIND_RESOURCES, ADAPT_CONTENT, TRANSLATE, REDISTRIBUTE }

    public static final class ResourceRef {
        private final ModuleVersionSnapshot.BindingKind kind;
        private final long resourceId;
        public ResourceRef(ModuleVersionSnapshot.BindingKind kind, long resourceId) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.resourceId = ContractChecks.positive(resourceId, "resourceId");
        }
        public ModuleVersionSnapshot.BindingKind getKind() { return kind; }
        public long getResourceId() { return resourceId; }
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ResourceRef)) return false;
            ResourceRef that = (ResourceRef) other;
            return kind == that.kind && resourceId == that.resourceId;
        }
        @Override
        public int hashCode() { return Objects.hash(kind, resourceId); }
    }

    private final BuyerPrincipal buyer;
    private final String requestId;
    private final String licenseId;
    private final String moduleId;
    private final String versionId;
    private final String expectedContentHash;
    private final String expectedOfferVersion;
    private final String expectedTermsHash;
    private final long draftId;
    private final long expectedDraftRevision;
    private final boolean commercialUse;
    private final Set<Change> changes;
    private final Map<String, ResourceRef> bindings;

    public ModuleInstallRequest(BuyerPrincipal buyer, String requestId, String licenseId,
                                String moduleId, String versionId, String expectedContentHash,
                                String expectedOfferVersion, String expectedTermsHash, long draftId,
                                long expectedDraftRevision, boolean commercialUse, Set<Change> changes,
                                Map<String, ResourceRef> bindings) {
        this.buyer = Objects.requireNonNull(buyer, "buyer");
        this.requestId = ContractChecks.text(requestId, "requestId");
        this.licenseId = ContractChecks.text(licenseId, "licenseId");
        this.moduleId = ContractChecks.text(moduleId, "moduleId");
        this.versionId = ContractChecks.text(versionId, "versionId");
        this.expectedContentHash = ContractChecks.hash(expectedContentHash);
        this.expectedOfferVersion = ContractChecks.text(expectedOfferVersion, "expectedOfferVersion");
        this.expectedTermsHash = ContractChecks.hash(expectedTermsHash);
        this.draftId = ContractChecks.positive(draftId, "draftId");
        this.expectedDraftRevision = ContractChecks.positive(expectedDraftRevision, "expectedDraftRevision");
        this.commercialUse = commercialUse;
        this.changes = ContractChecks.set(changes, "changes");
        this.bindings = ContractChecks.map(bindings, "bindings");
        for (String placeholder : this.bindings.keySet()) ContractChecks.text(placeholder, "placeholder");
    }

    public BuyerPrincipal getBuyer() { return buyer; }
    public String getRequestId() { return requestId; }
    public String getLicenseId() { return licenseId; }
    public String getModuleId() { return moduleId; }
    public String getVersionId() { return versionId; }
    public String getExpectedContentHash() { return expectedContentHash; }
    public String getExpectedOfferVersion() { return expectedOfferVersion; }
    public String getExpectedTermsHash() { return expectedTermsHash; }
    public long getDraftId() { return draftId; }
    public long getExpectedDraftRevision() { return expectedDraftRevision; }
    public boolean isCommercialUse() { return commercialUse; }
    public Set<Change> getChanges() { return changes; }
    public Map<String, ResourceRef> getBindings() { return bindings; }
}
