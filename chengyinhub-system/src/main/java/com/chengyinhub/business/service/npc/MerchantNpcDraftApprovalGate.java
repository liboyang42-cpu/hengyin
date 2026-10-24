package com.chengyinhub.business.service.npc;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.service.impl.AiThemeDraftRequestService;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.ForbiddenAiCommerce;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.Origin;

/**
 * Exclusive AI-draft → authorizer → original review → publish → player-consume
 * path. Matches iOS {@code MerchantNPCDraftApprovalGate}. Does not own order /
 * publish writers, AiGateway public auth, JWT/RBAC, or topic runtime.
 *
 * <p>The original review/publish writer is an injected port. The default is
 * {@link WaitingSourcePublisher}: unsigned originals v12 / 232-file pack /
 * {@code e16c2c5} stay {@code WAITING_SOURCE} and are not rewritten. Missing
 * {@code requestId} stays Java {@code null}, never {@code ""}.
 */
public final class MerchantNpcDraftApprovalGate {

    public static final String KNOWLEDGE_DRAFT_HTTP = "MerchantNpcKnowledgeDraftService.http";
    public static final String KNOWLEDGE_REVIEW_AUTHORITY = "MerchantNpcKnowledgeReviewAuthority.route";
    public static final String AI_GATEWAY_PUBLIC_AUTH = "AiGateway.publicAuth";
    /** Existing public draft read. Not a publish, price, stock, or reward writer. */
    public static final String AI_THEME_DRAFT_READ = "AiThemeDraftRequestService.read";
    public static final String UNSIGNED_V12 = "v12";
    public static final String UNSIGNED_FILE_PACK_232 = "232";
    public static final String UNSIGNED_DOT_SHA = "e16c2c5";

    /**
     * Remote originals (Questify v12, 232-file pack, unpushed D4 SHA e16c2c5)
     * were not signed onto this tree. Do not rewrite them from this exclusive.
     */
    public static final boolean UNSIGNED_ORIGINALS_PRESENT = false;

    /** Existing player-consume chat path only. Knowledge review/publish HTTP is not invented here. */
    public static final Set<String> SERVED_PATHS = Set.of("/api/ai/npc/merchant-chat");

    public enum Phase {
        IDLE, AI_DRAFT, AUTHORIZER_VERIFIED, ORIGINAL_REVIEW_PENDING, PUBLISHED, WAITING_SOURCE
    }

    public enum Audience {
        PLAYER_PUBLIC_NPC, MERCHANT_PRIVATE_ASSISTANT, THEME_RUN
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED, WAITING_SOURCE
    }

    public enum Failure {
        WAITING_SOURCE,
        AUTHORIZER_REQUIRED,
        ORIGINAL_REVIEW_REQUIRED,
        UNPUBLISHED,
        AI_CANNOT_PRICE,
        AI_CANNOT_DEDUCT_STOCK,
        AI_CANNOT_AWARD,
        AI_CANNOT_CERTIFY_PRESENCE,
        AI_UNAVAILABLE,
        ISOLATION_VIOLATION,
        QUALIFICATION_REVOKED,
        INVALID_DRAFT,
        STALE_SCOPE;

        public static Failure commerce(ForbiddenAiCommerce mutation) {
            if (mutation == null) {
                return AI_CANNOT_PRICE;
            }
            return switch (mutation) {
                case PRICE -> AI_CANNOT_PRICE;
                case DEDUCT_STOCK -> AI_CANNOT_DEDUCT_STOCK;
                case AWARD -> AI_CANNOT_AWARD;
                case CERTIFY_PRESENCE -> AI_CANNOT_CERTIFY_PRESENCE;
            };
        }
    }

    public record Scope(long accountId, long merchantRowId, String namespace) {
        public Scope {
            namespace = MerchantNpcKnowledgeDraft.blankToNull(namespace);
        }
    }

    public record Authorizer(long accountId, long merchantRowId) {
        public boolean matches(Scope scope) {
            return scope != null && accountId > 0L
                    && accountId == scope.accountId()
                    && merchantRowId == scope.merchantRowId();
        }
    }

    public record OriginalReviewTicket(String requestId, Scope scope, String factsHash, ReviewStatus status) {
        public OriginalReviewTicket {
            requestId = MerchantNpcKnowledgeDraft.blankToNull(requestId);
            factsHash = MerchantNpcKnowledgeDraft.blankToNull(factsHash);
            status = status == null ? ReviewStatus.WAITING_SOURCE : status;
        }
    }

    public record PublishedRevision(String revisionId, String requestId, Scope scope, byte[] facts, String factsHash) {
        public PublishedRevision {
            revisionId = MerchantNpcKnowledgeDraft.blankToNull(revisionId);
            requestId = MerchantNpcKnowledgeDraft.blankToNull(requestId);
            facts = facts == null ? new byte[0] : Arrays.copyOf(facts, facts.length);
            factsHash = MerchantNpcKnowledgeDraft.blankToNull(factsHash);
        }

        public byte[] facts() {
            return Arrays.copyOf(facts, facts.length);
        }
    }

    public record ThemeMainline(boolean completable, boolean fakeAiSuccess, String approvedFactsHash, byte[] approvedCopy) {
        public ThemeMainline {
            approvedFactsHash = MerchantNpcKnowledgeDraft.blankToNull(approvedFactsHash);
            approvedCopy = approvedCopy == null ? null : Arrays.copyOf(approvedCopy, approvedCopy.length);
            fakeAiSuccess = false;
        }

        public byte[] approvedCopy() {
            return approvedCopy == null ? null : Arrays.copyOf(approvedCopy, approvedCopy.length);
        }
    }

    public record RequestIdView(String requestId, Failure failure) {
        public RequestIdView {
            requestId = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        }
    }

    public record ReviewVisibility(String requestId, ReviewStatus status, boolean rewrittenToApproved) {
        public ReviewVisibility {
            requestId = MerchantNpcKnowledgeDraft.blankToNull(requestId);
            rewrittenToApproved = false;
        }
    }

    /**
     * Original knowledge review/publish writer. Default implementation is
     * WAITING_SOURCE, not a successful stub. Do not invent HTTP that does not exist.
     */
    public interface OriginalReviewPublishing {
        OriginalReviewTicket submitForOriginalReview(MerchantNpcKnowledgeDraft draft, String requestId, Scope scope);

        PublishedRevision publishApproved(OriginalReviewTicket ticket, Scope scope);
    }

    /**
     * Existing draft query. The only production delegate is
     * {@link AiThemeDraftRequestService#read}. Missing port is WAITING_SOURCE.
     */
    public interface DraftQuery {
        AiThemeDraftRequestState read(Long memberId, String requestId);
    }

    public static final class AiThemeDraftRequestQuery implements DraftQuery {
        private final AiThemeDraftRequestService drafts;

        public AiThemeDraftRequestQuery(AiThemeDraftRequestService drafts) {
            this.drafts = drafts;
        }

        @Override
        public AiThemeDraftRequestState read(Long memberId, String requestId) {
            if (drafts == null) {
                throw GateException.waitingSource(AI_THEME_DRAFT_READ, requestId);
            }
            return drafts.read(memberId, requestId);
        }
    }

    public static final class WaitingSourcePublisher implements OriginalReviewPublishing {
        @Override
        public OriginalReviewTicket submitForOriginalReview(MerchantNpcKnowledgeDraft draft, String requestId, Scope scope) {
            throw GateException.waitingSource(KNOWLEDGE_REVIEW_AUTHORITY, requestId);
        }

        @Override
        public PublishedRevision publishApproved(OriginalReviewTicket ticket, Scope scope) {
            String requestId = ticket == null ? null : ticket.requestId();
            throw GateException.waitingSource(KNOWLEDGE_DRAFT_HTTP, requestId);
        }
    }

    public static final class GateException extends RuntimeException {
        private final Failure failure;
        private final String capability;
        private final String requestId;

        private GateException(Failure failure, String capability, String requestId) {
            super(failure == null ? Failure.WAITING_SOURCE.name() : failure.name());
            this.failure = failure == null ? Failure.WAITING_SOURCE : failure;
            this.capability = MerchantNpcKnowledgeDraft.blankToNull(capability);
            this.requestId = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        }

        public static GateException of(Failure failure) {
            return new GateException(failure, null, null);
        }

        public static GateException waitingSource(String capability, String requestId) {
            return new GateException(Failure.WAITING_SOURCE, capability, requestId);
        }

        public Failure failure() {
            return failure;
        }

        public String capability() {
            return capability;
        }

        public String requestId() {
            return requestId;
        }

        public boolean isWaitingSource() {
            return failure == Failure.WAITING_SOURCE;
        }
    }

    private final Scope scope;
    private final Supplier<Scope> currentScope;
    private final OriginalReviewPublishing publisher;
    private final MerchantNpcPublicPrivateIsolation isolation;
    private final MerchantNpcQualificationRevoke qualification;
    private final DraftQuery draftQuery;
    private final Map<String, PublishedRevision> publishedByRequestId = new LinkedHashMap<String, PublishedRevision>();

    private Phase phase = Phase.IDLE;
    private Origin origin;
    private MerchantNpcKnowledgeDraft draft;
    private Authorizer authorizer;
    private OriginalReviewTicket reviewTicket;
    private PublishedRevision published;
    private Failure failure;

    public MerchantNpcDraftApprovalGate(Scope scope,
                                        OriginalReviewPublishing publisher,
                                        MerchantNpcPublicPrivateIsolation isolation,
                                        MerchantNpcQualificationRevoke qualification,
                                        Supplier<Scope> currentScope) {
        this(scope, publisher, isolation, qualification, currentScope, null);
    }

    public MerchantNpcDraftApprovalGate(Scope scope,
                                        OriginalReviewPublishing publisher,
                                        MerchantNpcPublicPrivateIsolation isolation,
                                        MerchantNpcQualificationRevoke qualification,
                                        Supplier<Scope> currentScope,
                                        DraftQuery draftQuery) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.publisher = publisher == null ? new WaitingSourcePublisher() : publisher;
        this.isolation = isolation == null ? new MerchantNpcPublicPrivateIsolation() : isolation;
        this.qualification = qualification == null ? new MerchantNpcQualificationRevoke() : qualification;
        this.currentScope = currentScope == null ? () -> this.scope : currentScope;
        this.draftQuery = draftQuery;
        this.draft = MerchantNpcKnowledgeDraft.aiDraft(null, List.of());
    }

    public MerchantNpcDraftApprovalGate(Scope scope) {
        this(scope, null, new MerchantNpcPublicPrivateIsolation(), new MerchantNpcQualificationRevoke(), () -> scope);
    }

    public Phase phase() {
        return phase;
    }

    public Origin origin() {
        return origin;
    }

    public MerchantNpcKnowledgeDraft draft() {
        return draft;
    }

    public Authorizer authorizer() {
        return authorizer;
    }

    public OriginalReviewTicket reviewTicket() {
        return reviewTicket;
    }

    public PublishedRevision published() {
        return published;
    }

    public Failure failure() {
        return failure;
    }

    public Scope scope() {
        return scope;
    }

    public MerchantNpcPublicPrivateIsolation isolation() {
        return isolation;
    }

    public MerchantNpcQualificationRevoke qualification() {
        return qualification;
    }

    public boolean isCurrent() {
        return Objects.equals(currentScope.get(), scope) && qualification.allowsServing(scope);
    }

    public boolean themeMainlineAvailableWhenAiUnavailable() {
        return MerchantNpcPublicPrivateIsolation.THEME_MAINLINE_AVAILABLE_WHEN_AI_UNAVAILABLE;
    }

    public void ingestAiDraft(MerchantNpcKnowledgeDraft value) {
        guardCurrent();
        if (value == null || !value.validationIssues().isEmpty() || value.encodesLiveCommerceMutation()) {
            throw fail(Failure.INVALID_DRAFT);
        }
        this.draft = value;
        this.origin = Origin.AI;
        this.authorizer = null;
        this.reviewTicket = null;
        this.published = null;
        this.phase = Phase.AI_DRAFT;
        this.failure = null;
        qualification.rememberPrivate(value, scope);
        isolation.retainPrivate(value, scope);
    }

    public void requestAiDraftGeneration() {
        throw failWaiting(KNOWLEDGE_DRAFT_HTTP, draft == null ? null : draft.requestId());
    }

    public void applyAiCommerce(ForbiddenAiCommerce mutation) {
        if (draft != null) {
            draft.applyingAiCommerce(mutation);
        }
        throw fail(Failure.commerce(mutation));
    }

    public void noteAiUnavailable() {
        this.failure = Failure.AI_UNAVAILABLE;
    }

    public ThemeMainline themeMainlineWhenAiUnavailable() {
        noteAiUnavailable();
        return isolation.themeMainlineWhenAiUnavailable(scope, qualification, failure);
    }

    public void verifyByAuthorizer(Authorizer value) {
        guardCurrent();
        if (phase != Phase.AI_DRAFT && phase != Phase.AUTHORIZER_VERIFIED || origin != Origin.AI) {
            throw fail(Failure.UNPUBLISHED);
        }
        if (value == null || !value.matches(scope)) {
            throw fail(Failure.AUTHORIZER_REQUIRED);
        }
        this.authorizer = value;
        this.phase = Phase.AUTHORIZER_VERIFIED;
        this.failure = null;
    }

    public OriginalReviewTicket submitOriginalReview(String requestId) {
        guardCurrent();
        if (phase != Phase.AUTHORIZER_VERIFIED || authorizer == null || !authorizer.matches(scope)) {
            throw fail(Failure.AUTHORIZER_REQUIRED);
        }
        String normalized = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        if (normalized == null) {
            throw failWaiting(KNOWLEDGE_REVIEW_AUTHORITY, null);
        }
        if (!UNSIGNED_ORIGINALS_PRESENT) {
            try {
                OriginalReviewTicket ticket = publisher.submitForOriginalReview(draft, normalized, scope);
                guardCurrent();
                this.reviewTicket = ticket;
                this.phase = Phase.ORIGINAL_REVIEW_PENDING;
                this.failure = null;
                isolation.rememberReview(normalized, ReviewStatus.PENDING);
                return ticket;
            } catch (GateException ex) {
                throw record(ex);
            } catch (RuntimeException ex) {
                throw failWaiting(KNOWLEDGE_REVIEW_AUTHORITY, normalized);
            }
        }
        throw failWaiting(KNOWLEDGE_REVIEW_AUTHORITY, normalized);
    }

    public PublishedRevision publishApproved() {
        guardCurrent();
        if (authorizer == null || !authorizer.matches(scope)) {
            throw fail(Failure.AUTHORIZER_REQUIRED);
        }
        if (reviewTicket == null || phase != Phase.ORIGINAL_REVIEW_PENDING) {
            throw fail(Failure.ORIGINAL_REVIEW_REQUIRED);
        }
        try {
            PublishedRevision revision = publisher.publishApproved(reviewTicket, scope);
            guardCurrent();
            this.published = revision;
            this.phase = Phase.PUBLISHED;
            this.failure = null;
            isolation.publish(revision, scope);
            qualification.rememberPublished(revision, scope);
            if (revision.requestId() != null) {
                publishedByRequestId.put(revision.requestId(), revision);
            }
            return revision;
        } catch (GateException ex) {
            throw record(ex);
        } catch (RuntimeException ex) {
            throw failWaiting(KNOWLEDGE_DRAFT_HTTP, reviewTicket.requestId());
        }
    }

    public byte[] playerConsume() {
        if (!qualification.allowsServing(scope)) {
            throw fail(Failure.QUALIFICATION_REVOKED);
        }
        guardCurrent();
        if (phase != Phase.PUBLISHED) {
            throw fail(Failure.UNPUBLISHED);
        }
        return isolation.playerProjection(scope, qualification);
    }

    public PublishedRevision requeryPublished(String requestId) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        if (id == null) {
            return null;
        }
        return publishedByRequestId.get(id);
    }

    /**
     * Read one existing draft through {@link AiThemeDraftRequestService#read}.
     * A missing port throws {@link Failure#WAITING_SOURCE} for {@link #AI_THEME_DRAFT_READ}.
     * A never-seen draft id returns null and does not occupy {@code privateScopes} or publish state.
     * This path does not publish, price, deduct stock, or issue rewards.
     */
    public AiThemeDraftRequestState projectFromDraftQuery(String draftId) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(draftId);
        if (draftQuery == null) {
            throw GateException.waitingSource(AI_THEME_DRAFT_READ, id);
        }
        if (id == null) {
            return null;
        }
        AiThemeDraftRequestState state = draftQuery.read(Long.valueOf(scope.accountId()), id);
        if (state == null || !id.equals(state.getRequestId())
                || AiThemeDraftRequestState.NOT_FOUND.equals(state.getState())
                || state.getResult() == null || state.getResult().getDraft() == null) {
            return null;
        }
        return state;
    }

    /**
     * Missing requestId stays Java null. Callers must not treat blank as a new id.
     */
    public RequestIdView inspectRequestId(String requestId) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        if (id == null) {
            return new RequestIdView(null, Failure.WAITING_SOURCE);
        }
        return new RequestIdView(id, null);
    }

    public ReviewVisibility visibilityForRequest(String requestId, boolean currentPublishedProjectionApproved) {
        String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
        ReviewStatus status = isolation.reviewStatusForRequest(id, currentPublishedProjectionApproved);
        return new ReviewVisibility(id, status, false);
    }

    public void rememberRejectedReview(String requestId) {
        isolation.rememberReview(requestId, ReviewStatus.REJECTED);
    }

    public void refuseAiGatewayPublicAuth() {
        throw failWaiting(AI_GATEWAY_PUBLIC_AUTH, null);
    }

    public void refuseUnservedKnowledgePath(String path) {
        throw failWaiting(path, null);
    }

    public GateException consumeUnsignedOriginal(String marker) {
        return GateException.waitingSource(
                UNSIGNED_ORIGINALS_PRESENT ? marker : unsignedCapability(marker),
                null);
    }

    private static String unsignedCapability(String marker) {
        if (UNSIGNED_V12.equals(marker) || UNSIGNED_FILE_PACK_232.equals(marker) || UNSIGNED_DOT_SHA.equals(marker)) {
            return "unsigned-original:" + marker;
        }
        return "unsigned-original";
    }

    private void guardCurrent() {
        if (!isCurrent()) {
            throw fail(Failure.STALE_SCOPE);
        }
    }

    private GateException fail(Failure error) {
        this.failure = error;
        if (error == Failure.WAITING_SOURCE) {
            this.phase = Phase.WAITING_SOURCE;
        }
        throw GateException.of(error);
    }

    private GateException failWaiting(String capability, String requestId) {
        this.failure = Failure.WAITING_SOURCE;
        this.phase = Phase.WAITING_SOURCE;
        throw GateException.waitingSource(capability, requestId);
    }

    private GateException record(GateException error) {
        this.failure = error.failure();
        if (error.isWaitingSource()) {
            this.phase = Phase.WAITING_SOURCE;
        }
        throw error;
    }

}
