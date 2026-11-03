package com.chengyinhub.business.service.npc;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Scope;

/**
 * Qualification loss clears public/private NPC caches so an old draft cannot
 * serve. This is the isolation store: revoke actually stops later consume.
 */
public final class MerchantNpcQualificationRevoke {

    private final Set<String> revoked = new LinkedHashSet<String>();
    private final Map<String, PublishedRevision> publicCache = new LinkedHashMap<String, PublishedRevision>();
    private final Map<String, MerchantNpcKnowledgeDraft> privateDrafts = new LinkedHashMap<String, MerchantNpcKnowledgeDraft>();

    public String key(Scope scope) {
        if (scope == null) {
            return "merchant-npc-qual:null";
        }
        String namespace = scope.namespace() == null ? "null" : scope.namespace();
        return "merchant-npc-qual:" + namespace.length() + ":" + namespace
                + ":account:" + scope.accountId() + ":row:" + scope.merchantRowId();
    }

    public void rememberPrivate(MerchantNpcKnowledgeDraft draft, Scope scope) {
        if (scope == null || isRevoked(scope)) {
            return;
        }
        privateDrafts.put(key(scope), draft);
    }

    public void rememberPublished(PublishedRevision revision, Scope scope) {
        if (scope == null || isRevoked(scope) || revision == null || !scope.equals(revision.scope())) {
            return;
        }
        publicCache.put(key(scope), revision);
        privateDrafts.remove(key(scope));
    }

    public void revoke(Scope scope) {
        if (scope == null) {
            return;
        }
        String identity = key(scope);
        revoked.add(identity);
        publicCache.remove(identity);
        privateDrafts.remove(identity);
    }

    public boolean isRevoked(Scope scope) {
        return scope != null && revoked.contains(key(scope));
    }

    public boolean allowsServing(Scope scope) {
        return !isRevoked(scope);
    }

    public PublishedRevision cachedPublic(Scope scope) {
        if (scope == null || isRevoked(scope)) {
            return null;
        }
        return publicCache.get(key(scope));
    }

    public MerchantNpcKnowledgeDraft cachedPrivate(Scope scope) {
        if (scope == null || isRevoked(scope)) {
            return null;
        }
        return privateDrafts.get(key(scope));
    }
}
