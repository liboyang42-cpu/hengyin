package com.chengyinhub.business.marketplace.editor;

import java.util.*;

/** Closed historical projection. There is deliberately no licensed text, URL, HTML or raw rights JSON. */
public final class InstalledDraftReceipts {
    public enum Availability { HISTORICAL_RECEIPTS_ONLY, NOT_ENABLED, OWNER_ONLY }
    public enum Binding { EXACT_REVISION, STALE_BINDING, BINDING_UNVERIFIABLE }
    private final Availability availability;
    private final List<Receipt> receipts;
    private final boolean hasMore;
    public InstalledDraftReceipts(Availability availability, List<Receipt> receipts, boolean hasMore) {
        this.availability=Objects.requireNonNull(availability);
        this.receipts=Collections.unmodifiableList(new ArrayList<Receipt>(receipts)); this.hasMore=hasMore;
    }
    public static InstalledDraftReceipts unavailable(Availability availability) {
        if (availability==Availability.HISTORICAL_RECEIPTS_ONLY) throw new IllegalArgumentException("not unavailable");
        return new InstalledDraftReceipts(availability,Collections.<Receipt>emptyList(),false);
    }
    public Availability getAvailability(){return availability;}
    public List<Receipt> getReceipts(){return receipts;}
    public boolean isHasMore(){return hasMore;}
    public String getContentUseStatus(){return "POST_INSTALL_POLICY_UNAVAILABLE";}
    public boolean isTextPreviewAllowed(){return false;}
    public boolean isEditingAllowed(){return false;}
    public boolean isExportAllowed(){return false;}
    public boolean isPublicationAllowed(){return false;}
    public boolean isExecutionAllowed(){return false;}
    public boolean isCommercialUseAllowed(){return false;}

    public static final class Receipt {
        private final long installationId,targetDraftId,installedTargetVersion;
        private final String installedAt,versionId,contentHash,termsHash,installedTargetPayloadHash;
        private final Binding binding;
        public Receipt(long installationId,long targetDraftId,long installedTargetVersion,String installedAt,
                String versionId,String contentHash,String termsHash,String installedTargetPayloadHash,Binding binding) {
            this.installationId=installationId;this.targetDraftId=targetDraftId;this.installedTargetVersion=installedTargetVersion;
            this.installedAt=installedAt;this.versionId=versionId;this.contentHash=contentHash;this.termsHash=termsHash;
            this.installedTargetPayloadHash=installedTargetPayloadHash;this.binding=binding;
        }
        public long getInstallationId(){return installationId;}
        public long getTargetDraftId(){return targetDraftId;}
        public long getInstalledTargetVersion(){return installedTargetVersion;}
        public String getInstalledAt(){return installedAt;}
        public String getVersionId(){return versionId;}
        public String getContentHash(){return contentHash;}
        public String getTermsHash(){return termsHash;}
        public String getInstalledTargetPayloadHash(){return installedTargetPayloadHash;}
        public Binding getBinding(){return binding;}
        public String getEvidenceKind(){return "HISTORICAL_INSTALLATION";}
    }
}
