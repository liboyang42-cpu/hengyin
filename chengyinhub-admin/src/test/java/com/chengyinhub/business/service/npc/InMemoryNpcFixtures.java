package com.chengyinhub.business.service.npc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Authorizer;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.GateException;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.OriginalReviewPublishing;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.OriginalReviewTicket;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.PublishedRevision;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.ReviewStatus;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.Scope;
import com.chengyinhub.business.service.npc.MerchantNpcDraftApprovalGate.WaitingSourcePublisher;
import com.chengyinhub.business.service.npc.MerchantNpcKnowledgeDraft.Product;

/**
 * JUnit 5 isolation fixtures. In-memory only: no MySQL, no HTTP, no Spring.
 */
public final class InMemoryNpcFixtures {

    public static final String REQUEST_ID = "c06-journey-npc-draft";
    public static final String NAMESPACE = "synthetic.invalid";

    private final Scope scope;
    private final Authorizer authorizer;
    private final MerchantNpcPublicPrivateIsolation isolation;
    private final MerchantNpcQualificationRevoke qualification;

    public InMemoryNpcFixtures() {
        this.scope = new Scope(901L, 31L, NAMESPACE);
        this.authorizer = new Authorizer(901L, 31L);
        this.isolation = new MerchantNpcPublicPrivateIsolation();
        this.qualification = new MerchantNpcQualificationRevoke();
    }

    public static InMemoryNpcFixtures synthetic() {
        return new InMemoryNpcFixtures();
    }

    public Scope scope() {
        return scope;
    }

    public Authorizer authorizer() {
        return authorizer;
    }

    public MerchantNpcPublicPrivateIsolation isolation() {
        return isolation;
    }

    public MerchantNpcQualificationRevoke qualification() {
        return qualification;
    }

    public String requestId() {
        return REQUEST_ID;
    }

    public MerchantNpcKnowledgeDraft draft() {
        return draft("Tea");
    }

    public MerchantNpcKnowledgeDraft draft(String name) {
        return MerchantNpcKnowledgeDraft.aiDraft(REQUEST_ID,
                List.of(new Product(name, "2500", "CNY")));
    }

    public MerchantNpcKnowledgeDraft draftWithoutRequestId() {
        return MerchantNpcKnowledgeDraft.aiDraft(null,
                List.of(new Product("Tea", "2500", "CNY")));
    }

    public MerchantNpcDraftApprovalGate gateWithRecordingPublisher() {
        return new MerchantNpcDraftApprovalGate(scope, new RecordingPublisher(),
                isolation, qualification, () -> scope);
    }

    public MerchantNpcDraftApprovalGate gateWithDefaultWaitingSource() {
        return new MerchantNpcDraftApprovalGate(scope, new WaitingSourcePublisher(),
                new MerchantNpcPublicPrivateIsolation(), new MerchantNpcQualificationRevoke(),
                () -> scope);
    }

    public Authorizer wrongAuthorizer() {
        return new Authorizer(8L, scope.merchantRowId());
    }

    /**
     * Isolation publisher. Records facts by requestId in memory; never calls HTTP.
     */
    public static final class RecordingPublisher implements OriginalReviewPublishing {
        private final Map<String, byte[]> facts = new LinkedHashMap<String, byte[]>();
        private int submitted;
        private int publishedCount;

        public int submitted() {
            return submitted;
        }

        public int publishedCount() {
            return publishedCount;
        }

        @Override
        public OriginalReviewTicket submitForOriginalReview(MerchantNpcKnowledgeDraft draft, String requestId, Scope scope) {
            String id = MerchantNpcKnowledgeDraft.blankToNull(requestId);
            if (id == null) {
                throw GateException.waitingSource(
                        MerchantNpcDraftApprovalGate.KNOWLEDGE_REVIEW_AUTHORITY, null);
            }
            byte[] data = draft.validatedPublicFactsJson();
            facts.put(id, data);
            submitted++;
            return new OriginalReviewTicket(id, scope, MerchantNpcKnowledgeDraft.sha256Hex(data),
                    ReviewStatus.PENDING);
        }

        @Override
        public PublishedRevision publishApproved(OriginalReviewTicket ticket, Scope scope) {
            if (ticket == null || scope == null || !scope.equals(ticket.scope())) {
                throw GateException.of(MerchantNpcDraftApprovalGate.Failure.ORIGINAL_REVIEW_REQUIRED);
            }
            String id = ticket.requestId();
            byte[] data = id == null ? null : facts.get(id);
            if (data == null || !MerchantNpcKnowledgeDraft.sha256Hex(data).equals(ticket.factsHash())) {
                throw GateException.of(MerchantNpcDraftApprovalGate.Failure.ORIGINAL_REVIEW_REQUIRED);
            }
            publishedCount++;
            return new PublishedRevision(UUID.randomUUID().toString(), id, scope, data, ticket.factsHash());
        }
    }
}
