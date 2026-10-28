package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyStory;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Ordered immutable identity snapshot; it contains neither DTOs nor executable work. */
public final class TopicAuthoredGraphIds {
    public final Long openingChapterId;
    public final Map<String, Long> clientToNew;
    public final Map<String, Long> createdClientToNew;
    public final Map<Long, Long> oldToNew;
    public final Set<Long> newNodeIds;
    public final Map<Long, Long> chapterOldToNew;
    private final List<EndingChapterSnapshot> endings;

    public TopicAuthoredGraphIds(Long openingChapterId, List<EndingChapterSnapshot> endingChapters,
            Map<String, Long> clientToNew, Map<String, Long> createdClientToNew,
            Map<Long, Long> oldToNew, Set<Long> newNodeIds, Map<Long, Long> chapterOldToNew) {
        this.openingChapterId = openingChapterId;
        this.endings = Collections.unmodifiableList(new ArrayList<>(endingChapters));
        this.clientToNew = Collections.unmodifiableMap(new LinkedHashMap<>(clientToNew));
        this.createdClientToNew = Collections.unmodifiableMap(new LinkedHashMap<>(createdClientToNew));
        this.oldToNew = Collections.unmodifiableMap(new LinkedHashMap<>(oldToNew));
        this.newNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(newNodeIds));
        this.chapterOldToNew = Collections.unmodifiableMap(new LinkedHashMap<>(chapterOldToNew));
    }

    /** Never exposes the stored mutable JSON tree to a caller. */
    public List<JourneyStory.EndingChapter> endingChapters() {
        List<JourneyStory.EndingChapter> values = new ArrayList<>(endings.size());
        for (EndingChapterSnapshot ending : endings) {
            values.add(new JourneyStory.EndingChapter(ending.chapterId, ending.title, ending.summary,
                    ending.ending == null ? null : ending.ending.deepCopy()));
        }
        return Collections.unmodifiableList(values);
    }

    public static final class EndingChapterSnapshot {
        private final Long chapterId;
        private final String title;
        private final String summary;
        private final JsonNode ending;

        public EndingChapterSnapshot(Long chapterId, String title, String summary, JsonNode ending) {
            this.chapterId = chapterId;
            this.title = title;
            this.summary = summary;
            this.ending = ending == null ? null : ending.deepCopy();
        }
    }
}
