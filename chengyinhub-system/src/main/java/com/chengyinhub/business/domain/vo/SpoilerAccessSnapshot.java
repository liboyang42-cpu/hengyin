package com.chengyinhub.business.domain.vo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SpoilerAccessSnapshot {
    private final boolean revealAll;
    private final Set<Long> revealedContentIds;

    public SpoilerAccessSnapshot(boolean revealAll, Set<Long> revealedContentIds) {
        this.revealAll = revealAll;
        this.revealedContentIds = revealedContentIds == null
                ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(revealedContentIds));
    }

    public boolean shouldReveal(Long contentId) {
        return revealAll || (contentId != null && revealedContentIds.contains(contentId));
    }

    public boolean isRevealAll() { return revealAll; }
    public Set<Long> getRevealedContentIds() { return revealedContentIds; }
}
