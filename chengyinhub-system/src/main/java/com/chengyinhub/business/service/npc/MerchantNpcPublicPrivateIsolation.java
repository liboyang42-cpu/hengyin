package com.chengyinhub.business.service.npc;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Audience;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Failure;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Phase;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewStatus;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Scope;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ThemeMainline;

/**
 * Public store / theme-run NPC facts stay isolated from private operating drafts.
 * Theme mainline stays completable when AI is down by using an already-approved
 * copy, never a fake AI success.
 */
public final class MerchantNpcPublicPrivateIsolation {

    public static final boolean THEME_MAINLINE_AVAILABLE_WHEN_AI_UNAVAILABLE = true;

    private final Set<String> privateScopes = new LinkedHashSet<String>();
    private final Map<String, ReviewStatus> reviewsByRequestId = new LinkedHashMap<String, ReviewStatus>();

    public Audience audienceFor(Phase phase) {
        return phase == Phase.PUBLISHED ? Audience.PLAYER_PUBLIC_NPC : Audience.MERCHANT_PRIVATE_ASSISTANT;
    }

    public boolean visibilityIsPublic(Phase phase) {
        return phase == Phase.PUBLISHED;
    }

    public boolean themeRunMayProceed(Failure aiFailure) {
        return THEME_MAINLINE_AVAILABLE_WHEN_AI_UNAVAILABLE;
    }

    /**
     * Theme mainline uses the published approved copy when present. AI unavailability
     * never fabricates a successful draft.
     */
    public ThemeMainline themeMainlineWhenAiUnavailable(Scope scope,
                                                        MerchantNpcQualificationRevoke qualification,
                                                        Failure aiFailure) {
        if (!themeRunMayProceed(aiFailure)) {
            return new ThemeMainline(false, false, null, null);
        }
        PublishedRevision approved = qualification == null ? null : qualification.cachedPublic(scope);
        if (approved == null) {
            return new ThemeMainline(true, false, null, null);
        }
        return new ThemeMainline(true, false, approved.factsHash(), approved.facts());
    }

    public void retainPrivate(MerchantNpcKnowledgeDraft draft, Scope scope) {
        if (scope == null) {
            return;
        }
        privateScopes.add(key(scope));
    }

    public void publish(PublishedRevision revision, Scope scope) {
        if (revision == null || scope == null || !scope.equals(revision.scope())) {
            return;
        }
        privateScopes.remove(key(scope));
        if (revision.requestId() != null) {
            reviewsByRequestId.put(revision.requestId(), ReviewStatus.APPROVED);
        }
    }

    public void rememberReview(String requestId, ReviewStatus status) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        if (id == null || status == null) {
            return;
        }
        reviewsByRequestId.put(id, status);
    }

    /**
     * Current published-content projection must not rewrite this request's
     * rejected review into approved.
     */
    public ReviewStatus reviewStatusForRequest(String requestId, boolean currentPublishedProjectionApproved) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        if (id == null) {
            return null;
        }
        ReviewStatus stored = reviewsByRequestId.get(id);
        if (stored == ReviewStatus.REJECTED && currentPublishedProjectionApproved) {
            return ReviewStatus.REJECTED;
        }
        return stored;
    }

    public byte[] playerProjection(Scope scope, MerchantNpcQualificationRevoke qualification) {
        if (qualification == null || !qualification.allowsServing(scope)) {
            throw GateException.of(Failure.QUALIFICATION_REVOKED);
        }
        PublishedRevision revision = qualification.cachedPublic(scope);
        if (revision == null || scope == null || !scope.equals(revision.scope())) {
            throw GateException.of(Failure.UNPUBLISHED);
        }
        if (privateScopes.contains(key(scope))) {
            throw GateException.of(Failure.ISOLATION_VIOLATION);
        }
        return revision.facts();
    }

    public void rejectPrivateLeak(byte[] playerPayload, MerchantNpcKnowledgeDraft privateDraft, Phase phase) {
        if (phase == Phase.PUBLISHED) {
            return;
        }
        if (privateDraft == null || playerPayload == null) {
            return;
        }
        byte[] privateFacts;
        try {
            privateFacts = privateDraft.validatedPublicFactsJson();
        } catch (RuntimeException ignored) {
            return;
        }
        if (java.util.Arrays.equals(privateFacts, playerPayload)) {
            throw GateException.of(Failure.ISOLATION_VIOLATION);
        }
    }

    public static String key(Scope scope) {
        if (scope == null) {
            return "merchant-npc-iso:null";
        }
        String namespace = scope.namespace() == null ? "null" : scope.namespace();
        return "merchant-npc-iso:" + namespace.length() + ":" + namespace
                + ":account:" + scope.accountId() + ":row:" + scope.merchantRowId();
    }
}
