package com.chengyinhub.business.service.ai.scope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, inactive filtering contract. A future retriever must enforce these predicates BEFORE search.
 * Passing an in-memory test does not secure existing SQL, vector retrieval, chat or preview routes.
 */
public final class AiProjectionPolicy
{
    private AiProjectionPolicy() { }

    /** Retrieved content is data, including reviewed text that contains prompt injection. */
    public static final class Fragment
    {
        private final String sourceId;
        private final String untrustedText;
        private final String fingerprint;

        private Fragment(AiKnowledge source)
        {
            this.sourceId = source.getSourceId();
            this.untrustedText = source.getUntrustedText();
            this.fingerprint = source.fingerprint();
        }

        public String getSourceId() { return sourceId; }
        public String getUntrustedText() { return untrustedText; }
        public boolean isInstructionAuthority() { return false; }
    }

    public static final class KnowledgeProjection
    {
        private final AiScope scope;
        private final List<Fragment> fragments;
        private final Instant validUntil;
        private final String fingerprint;

        private KnowledgeProjection(AiScope scope, List<Fragment> fragments, Instant validUntil)
        {
            this.scope = scope;
            this.fragments = Collections.unmodifiableList(new ArrayList<>(fragments));
            this.validUntil = validUntil;
            List<String> identities = new ArrayList<>();
            for (Fragment fragment : fragments) identities.add(fragment.fingerprint);
            this.fingerprint = AiContractSupport.fingerprint(scope.getFingerprint(),
                    AiContractSupport.fingerprint(identities.toArray()), validUntil);
        }

        public List<Fragment> getFragments() { return fragments; }
        public Instant getValidUntil() { return validUntil; }
        String fingerprint() { return fingerprint; }
        AiScope scope() { return scope; }
    }

    public static final class MemoryProjection
    {
        private final AiScope scope;
        private final List<AiMemory> memories;
        private final Instant validUntil;
        private final String retentionFingerprint;
        private final String fingerprint;

        private MemoryProjection(AiScope scope, List<AiMemory> memories, Instant validUntil,
                                 AiRetentionPolicy retention)
        {
            this.scope = scope;
            this.memories = Collections.unmodifiableList(new ArrayList<>(memories));
            this.validUntil = validUntil;
            this.retentionFingerprint = retention.fingerprint();
            List<String> identities = new ArrayList<>();
            for (AiMemory memory : memories) identities.add(memory.fingerprint());
            this.fingerprint = AiContractSupport.fingerprint(scope.getFingerprint(),
                    AiContractSupport.fingerprint(identities.toArray()), validUntil, retentionFingerprint);
        }

        public List<AiMemory> getMemories() { return memories; }
        public Instant getValidUntil() { return validUntil; }
        String fingerprint() { return fingerprint; }
        String retentionFingerprint() { return retentionFingerprint; }
        AiScope scope() { return scope; }
    }

    public static final class ConversationProjection
    {
        private final AiConversationScope conversation;
        private final List<AiConversationData> items;
        private final Instant validUntil;
        private final String retentionFingerprint;
        private final String fingerprint;

        private ConversationProjection(AiConversationScope conversation, List<AiConversationData> items,
                                       Instant validUntil, AiRetentionPolicy retention)
        {
            this.conversation = conversation;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.validUntil = validUntil;
            this.retentionFingerprint = retention.fingerprint();
            List<String> identities = new ArrayList<>();
            for (AiConversationData item : items) identities.add(item.fingerprint());
            this.fingerprint = AiContractSupport.fingerprint(conversation.getScope().getFingerprint(),
                    conversation.getFingerprint(), AiContractSupport.fingerprint(identities.toArray()),
                    validUntil, retentionFingerprint);
        }

        public List<AiConversationData> getItems() { return items; }
        public Instant getValidUntil() { return validUntil; }
        AiConversationScope conversation() { return conversation; }
        String fingerprint() { return fingerprint; }
        String retentionFingerprint() { return retentionFingerprint; }
    }

    /** Always revalidate the CURRENT grant, even when stable memory/conversation identity is unchanged. */
    public static ConversationProjection conversation(AiConversationScope requested,
                                                       List<AiConversationData> candidates,
                                                       AiRetentionPolicy retention, Instant now)
    {
        Objects.requireNonNull(requested, "requested").getScope().requireActiveAt(now);
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(retention, "retention must be explicitly configured");
        List<AiConversationData> accepted = new ArrayList<>();
        Map<String, String> identities = new HashMap<>();
        Instant validUntil = requested.getScope().getValidUntil();
        for (AiConversationData item : candidates)
        {
            Objects.requireNonNull(item, "conversation item");
            if (item.isRetrievable(requested, retention, now))
            {
                String previous = identities.putIfAbsent(item.identity(), item.fingerprint());
                if (previous != null && !previous.equals(item.fingerprint()))
                    throw new IllegalArgumentException("conflicting conversation item identity");
                if (previous == null) accepted.add(item);
                validUntil = earlier(validUntil, item.getExpiresAt());
            }
        }
        return new ConversationProjection(requested, accepted, validUntil, retention);
    }

    public static KnowledgeProjection knowledge(AiScope requested, List<AiKnowledge> candidates,
                                                 List<PublicStoreProjection> publicStoreFacts, Instant now)
    {
        Objects.requireNonNull(requested, "requested").requireActiveAt(now);
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(publicStoreFacts, "publicStoreFacts");
        List<Fragment> accepted = new ArrayList<>();
        Map<String, String> sources = new HashMap<>();
        Instant validUntil = requested.getValidUntil();
        for (AiKnowledge candidate : candidates)
        {
            Objects.requireNonNull(candidate, "candidate");
            if (requested.sameScope(candidate.getScope()) && candidate.reviewedVisibleAt(now)
                    && permits(requested.getScenario(), candidate.getSourceKind()))
            {
                add(accepted, sources, candidate);
                validUntil = earlier(validUntil, candidate.getValidUntil());
            }
        }
        for (PublicStoreProjection projection : publicStoreFacts)
        {
            Objects.requireNonNull(projection, "publicStoreProjection");
            AiKnowledge source = projection.source();
            // Only explicit public projection can drop the store session's actor dimension.
            if (requested.getMerchantId() != null && requested.getMerchantId() == projection.getMerchantId()
                    && publicStoreAllowed(requested.getScenario()) && source.reviewedVisibleAt(now))
            {
                add(accepted, sources, source);
                validUntil = earlier(validUntil, earlier(source.getValidUntil(), source.getScope().getValidUntil()));
            }
        }
        return new KnowledgeProjection(requested, accepted, validUntil);
    }

    public static MemoryProjection memory(AiScope requested, List<AiMemory> candidates,
                                           AiRetentionPolicy retention, Instant now)
    {
        Objects.requireNonNull(requested, "requested").requireActiveAt(now);
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(retention, "retention must be explicitly configured");
        List<AiMemory> accepted = new ArrayList<>();
        Map<String, String> events = new HashMap<>();
        Instant validUntil = requested.getValidUntil();
        for (AiMemory candidate : candidates)
        {
            Objects.requireNonNull(candidate, "candidate");
            if (candidate.isRetrievable(requested, retention, now))
            {
                String previous = events.putIfAbsent(candidate.getSourceEventId(), candidate.fingerprint());
                if (previous != null && !previous.equals(candidate.fingerprint()))
                {
                    throw new IllegalArgumentException("conflicting memory for the same source event");
                }
                if (previous == null) accepted.add(candidate);
                validUntil = earlier(validUntil, candidate.getExpiresAt());
            }
        }
        return new MemoryProjection(requested, accepted, validUntil, retention);
    }

    private static void add(List<Fragment> accepted, Map<String, String> sources, AiKnowledge source)
    {
        String previous = sources.putIfAbsent(source.getSourceId(), source.fingerprint());
        if (previous != null && !previous.equals(source.fingerprint()))
        {
            throw new IllegalArgumentException("conflicting knowledge for the same source id");
        }
        if (previous == null) accepted.add(new Fragment(source));
    }

    private static boolean publicStoreAllowed(AiScenario scenario)
    {
        return scenario == AiScenario.STORE_PUBLIC_NPC || scenario == AiScenario.MERCHANT_PRIVATE_ASSISTANT
                || scenario.isTheme();
    }

    private static boolean permits(AiScenario scenario, AiKnowledge.SourceKind kind)
    {
        switch (scenario)
        {
            case PLATFORM_ASSISTANT: return kind == AiKnowledge.SourceKind.PLATFORM_INTERNAL;
            case CREATOR_ASSISTANT: return kind == AiKnowledge.SourceKind.CREATOR_AUTHORIZED;
            case MERCHANT_PRIVATE_ASSISTANT:
                return kind == AiKnowledge.SourceKind.MERCHANT_SALES
                        || kind == AiKnowledge.SourceKind.MERCHANT_EMPLOYEE
                        || kind == AiKnowledge.SourceKind.MERCHANT_CUSTOMER
                        || kind == AiKnowledge.SourceKind.MERCHANT_PRIVATE_OTHER;
            case THEME_NPC:
            case THEME_DIRECTOR: return kind == AiKnowledge.SourceKind.THEME_RELEASE;
            // Public base knowledge requires the explicit PublicStoreProjection factory.
            default: return false;
        }
    }

    static Instant earlier(Instant first, Instant second)
    {
        return first.isBefore(second) ? first : second;
    }
}
