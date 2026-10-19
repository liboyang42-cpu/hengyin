package com.chengyinhub.business.service.ai.npc;

import java.time.Instant;
import java.util.*;
import com.chengyinhub.business.service.ai.scope.*;
import com.chengyinhub.business.service.ai.npc.ScopedNpcRepository.*;

/**
 * Read-only grounding for the existing internal scoped route. This is not a publication registry.
 * The repository MUST prefilter approved/current sources before retrieval; it remains unwired in
 * production. Raw merchant descriptions, profile knowledge, private analytics and model output are
 * never substitutes for that source. Merchant/profile availability is re-read through real DAOs.
 */
final class NpcGroundingAssembler
{
    private final ScopedNpcRepository repository;
    private final MerchantNpcDirectory directory;

    NpcGroundingAssembler(ScopedNpcRepository repository, MerchantNpcDirectory directory)
    {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    Context assemble(Access access, AiScope scope, AiScope publicScope, Instant now)
    {
        scope.requireActiveAt(now);
        publicScope.requireActiveAt(now);
        if (access == null || access.state != State.ACTIVE || access.validUntil == null
                || !now.isBefore(access.validUntil)
                || publicScope.getScenario() != AiScenario.STORE_PUBLIC_NPC
                || !Long.valueOf(access.merchantId).equals(scope.getMerchantId())
                || !Long.valueOf(access.merchantId).equals(publicScope.getMerchantId())
                || !directory.matches(access.merchantId, access.ownerMemberId, access.profileId))
            throw new IllegalArgumentException("current merchant grounding authority required");
        if ((access.theme == null && !scope.sameScope(publicScope))
                || (access.theme != null && scope.getScenario() != AiScenario.THEME_NPC))
            throw new IllegalArgumentException("grounding scenario mismatch");

        List<AiKnowledge> publicRows = retrieve(new Query(publicScope, AiKnowledge.SourceKind.STORE_PUBLIC_FACT,
                access.publicSetVersion, access.publicPublicationReference, null, null,
                access.ownerMemberId, access.publicSourceIds), now);
        List<PublicStoreProjection> publicFacts = new ArrayList<>();
        for (AiKnowledge row : publicRows) publicFacts.add(PublicStoreProjection.fromReviewedPublicFact(row, now));
        List<AiKnowledge> themeRows = Collections.emptyList();
        if (access.theme != null)
        {
            Theme theme = access.theme;
            themeRows = retrieve(new Query(scope, AiKnowledge.SourceKind.THEME_RELEASE, theme.sourceSetVersion,
                    theme.publicationReference, theme.packId, theme.packHash, theme.themeOwnerMemberId,
                    theme.visibleSourceIds), now);
        }
        AiProjectionPolicy.KnowledgeProjection projection = AiProjectionPolicy.knowledge(scope, themeRows, publicFacts, now);
        if (projection.getFragments().isEmpty()
                || projection.getFragments().size() != publicRows.size() + themeRows.size())
            throw new IllegalArgumentException("incomplete grounding projection");
        List<AiKnowledge> rows = new ArrayList<>(themeRows);
        rows.addAll(publicRows);
        return new Context(projection, rows);
    }

    private List<AiKnowledge> retrieve(Query query, Instant now)
    {
        if (query.allowedSourceIds.isEmpty()) return Collections.emptyList();
        List<KnowledgeRow> rows = repository.retrieve(query, now);
        if (rows == null || rows.size() > query.limit) throw new IllegalArgumentException("invalid retrieval size");
        List<AiKnowledge> accepted = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (KnowledgeRow row : rows)
        {
            if (row == null || row.knowledge == null || !query.scope.sameScope(row.knowledge.getScope())
                    || row.knowledge.getSourceKind() != query.kind
                    || row.knowledge.getReview() != AiKnowledge.Review.APPROVED
                    || !query.allowedSourceIds.contains(row.knowledge.getSourceId())
                    || !Objects.equals(query.sourceSetVersion, row.sourceSetVersion)
                    || !Objects.equals(query.publicationReference, row.publicationReference)
                    || !Objects.equals(query.packId, row.packId) || !Objects.equals(query.packHash, row.packHash)
                    || row.approvingOwnerMemberId != query.approvingOwnerMemberId
                    || !identities.add(row.knowledge.getSourceId()))
                throw new IllegalArgumentException("retrieval crossed approved grounding boundary");
            accepted.add(row.knowledge);
        }
        accepted.sort(Comparator.comparing(AiKnowledge::getSourceId));
        return accepted;
    }

    /** Source references describe supplied evidence, not proof that a model's answer is correct. */
    static final class Context
    {
        private final AiProjectionPolicy.KnowledgeProjection projection;
        private final List<Map<String, String>> facts;
        private final List<String> sourceIds;

        private Context(AiProjectionPolicy.KnowledgeProjection projection, List<AiKnowledge> rows)
        {
            this.projection = projection;
            List<Map<String, String>> facts = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (AiKnowledge row : rows)
            {
                Map<String, String> fact = new LinkedHashMap<>();
                fact.put("sourceId", row.getSourceId());
                fact.put("sourceKind", row.getSourceKind().name());
                fact.put("revision", row.getRevision());
                fact.put("contentHash", row.getContentHash());
                fact.put("validUntil", row.getValidUntil().toString());
                fact.put("data", row.getUntrustedText());
                facts.add(Collections.unmodifiableMap(fact));
                ids.add(row.getSourceId());
            }
            this.facts = Collections.unmodifiableList(facts);
            this.sourceIds = Collections.unmodifiableList(ids);
        }

        AiProjectionPolicy.KnowledgeProjection getProjection() { return projection; }
        List<Map<String, String>> getFacts() { return facts; }
        List<String> getSourceIds() { return sourceIds; }
    }
}
