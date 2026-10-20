package com.chengyinhub.business.marketplace.contract;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable server-side provenance, not a public content projection or review authority.
 * The trusted adapter must load the exact reviewed canonical bytes, never a mutable latest template.
 * Bytes must include content, assets and placeholder definitions using an approved canonicalization.
 * This class checks the digest but does not invent that canonicalization or perform content review.
 */
public final class ModuleVersionSnapshot {
    public enum SourceKind { MEMBER_TEMPLATE, TOPIC_TEMPLATE }
    public enum BindingKind { LOCATION, PRODUCT }
    private final BuyerPrincipal creator;
    private final String moduleId;
    private final String versionId;
    private final String version;
    private final int schemaVersion;
    private final SourceKind sourceKind;
    private final long sourceId;
    private final String sourceRevision;
    private final String contentHash;
    private final String approvalReference;
    private final Map<String, BindingKind> requiredBindings;

    public ModuleVersionSnapshot(BuyerPrincipal creator, String moduleId, String versionId, String version, int schemaVersion,
                                 SourceKind sourceKind, long sourceId, String sourceRevision,
                                 byte[] canonicalContent, String approvedContentHash,
                                 String approvalReference, Map<String, BindingKind> requiredBindings) {
        this.creator = Objects.requireNonNull(creator, "creator");
        this.moduleId = ContractChecks.text(moduleId, "moduleId");
        this.versionId = ContractChecks.text(versionId, "versionId");
        this.version = ContractChecks.text(version, "version");
        this.schemaVersion = (int) ContractChecks.positive(schemaVersion, "schemaVersion");
        this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        this.sourceId = ContractChecks.positive(sourceId, "sourceId");
        this.sourceRevision = ContractChecks.text(sourceRevision, "sourceRevision");
        this.contentHash = ContractChecks.hash(approvedContentHash);
        if (!contentHash.equals(ContractChecks.sha256(canonicalContent))) {
            throw new IllegalArgumentException("reviewed content hash mismatch");
        }
        this.approvalReference = ContractChecks.text(approvalReference, "approvalReference");
        this.requiredBindings = ContractChecks.map(requiredBindings, "requiredBindings");
        for (String placeholder : this.requiredBindings.keySet()) ContractChecks.text(placeholder, "placeholder");
    }

    /**
     * Opt-in App package structural validation against this exact existing reviewed source.
     * Does not issue rights, ingest a publication, authorize an install or alter legacy serialization.
     */
    public Map<String, String> verifyPackageManifest(ModulePackageManifest manifest, String approvedManifestHash,
            java.util.Collection<ModulePackageManifest> dependencies, int runtimeVersion,
            java.util.Set<ModulePackageManifest.Capability> supportedCapabilities) {
        return ModulePackageManifest.verify(this, manifest, approvedManifestHash, dependencies,
                runtimeVersion, supportedCapabilities);
    }

    public BuyerPrincipal getCreator() { return creator; }
    public String getModuleId() { return moduleId; }
    public String getVersionId() { return versionId; }
    public String getVersion() { return version; }
    public int getSchemaVersion() { return schemaVersion; }
    public SourceKind getSourceKind() { return sourceKind; }
    public long getSourceId() { return sourceId; }
    public String getSourceRevision() { return sourceRevision; }
    public String getContentHash() { return contentHash; }
    public String getApprovalReference() { return approvalReference; }
    public Map<String, BindingKind> getRequiredBindings() { return requiredBindings; }
}
