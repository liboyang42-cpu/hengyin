package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.marketplace.editor.InstalledDraftReceipts;

/** Existing restore payload plus a non-persisted, read-only installation receipt projection. */
public final class ContentDraftRestoreView extends ContentDraft {
    private final InstalledDraftReceipts installedModules;
    public ContentDraftRestoreView(ContentDraft draft,InstalledDraftReceipts installedModules) {
        setId(draft.getId());setOwnerMemberId(draft.getOwnerMemberId());setBusinessType(draft.getBusinessType());
        setClientDraftKey(draft.getClientDraftKey());setSubjectId(draft.getSubjectId());setPayloadJson(draft.getPayloadJson());
        setPayloadHash(draft.getPayloadHash());setStatus(draft.getStatus());setVersion(draft.getVersion());
        setUpdatedByDevice(draft.getUpdatedByDevice());setPublishedResourceId(draft.getPublishedResourceId());
        setCreateTime(draft.getCreateTime());setUpdateTime(draft.getUpdateTime());this.installedModules=installedModules;
    }
    public InstalledDraftReceipts getInstalledModules(){return installedModules;}
}
