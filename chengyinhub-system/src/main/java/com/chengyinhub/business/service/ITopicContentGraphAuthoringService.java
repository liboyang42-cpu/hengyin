package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.service.support.TopicAuthoredGraphIds;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal content writer; the existing application facade retains authorization and orchestration. */
public interface ITopicContentGraphAuthoringService {
    boolean shouldCompileChapterFlow(boolean chapterFlowV2, Integer productType, List<ChapterDTO> chapters);
    TopicAuthoredGraphIds saveTopicChapters(List<ChapterDTO> chapters, Long topicId, String username,
        Long memberId, Integer productType, boolean chapterFlowV2, boolean allowBundledDraft);
    TopicAuthoredGraphIds compileChapterFlow(List<ChapterDTO> chapters, Long topicId, String username,
        Long memberId, boolean replaceExisting, boolean allowBundledDraft);
    void deleteTopicChapterFlow(Long topicId);
    Map<Long, Long> existingNodeUnlocks(Long topicId);
    Set<Long> selectHiddenNodeIds(Long topicId);
    void restoreHiddenNodes(Set<Long> hiddenNodeIds, Map<Long, Long> oldToNew);
    void remapRebuiltTemplateNodes(Long topicId, Map<Long, Long> nodeMap, Long ownerId);
    void restoreNodeUnlocks(Map<Long, Long> existingUnlocks, TopicAuthoredGraphIds ids);
    void persistJourneyStoryAfterChapterRebuild(Long topicId, String storedStory,
        TopicAuthoredGraphIds ids, String username);
    void persistCreatedRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids);
    void persistRebuiltRuntimeBindings(Long topicId, String runtimeRaw, TopicAuthoredGraphIds ids);
    void persistTemplateInteractionRules(Long topicId);
    void saveRouteDefinition(Long topicId, String requestedMode, String rawGraph,
        TopicAuthoredGraphIds ids, Long requestExpectedVersion, Long expectedVersion,
        Long targetVersion, Integer productType);
}
