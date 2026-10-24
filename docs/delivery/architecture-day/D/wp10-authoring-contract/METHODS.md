# 迁移方法与原生产调用点

来源固定为 `864e708e2f38130e21780485e462f8dc147bb76f`。行号属于该源。25 个方法定义（23 个名称）；本组同名重载的参数个数各不相同，调用点按参数个数区分；这是词法检索，不冒充 javac 类型绑定证明。所有方法当前为 private；未发现方法引用。外部入口与共享发布调用另见 CONTRACT.md。

| 原定义 | 拟定真实 owner | 全部内部调用点（caller:line） |
|---|---|---|
| `copyTemplateForOwner(Long templateId, Long newOwnerMemberId, Map<Long, Long> templateIdMap, boolean forceCopy)` :548 | TopicContentGraphAuthoringServiceImpl | `remapRebuiltTemplateNodes:3328` |
| `persistJourneyStoryAfterChapterRebuild(Long topicId, String storedStory, RouteNodeMaps routeNodeMaps, String username)` :3123 | TopicContentGraphAuthoringServiceImpl | `createTopicInternal:3784`, `rebuildTopicContent:4301` |
| `remapRebuiltTemplateNodes(Long topicId,Map<Long,Long> nodeMap,Long ownerId)` :3319 | TopicContentGraphAuthoringServiceImpl | `createTopicInternal:3783`, `rebuildTopicContent:4299` |
| `persistTemplateInteractionRules(Long topicId)` :3341 | TopicContentGraphAuthoringServiceImpl | `createTopicInternal:3795`, `rebuildTopicContent:4309` |
| `existingNodeUnlocks(Long topicId)` :4403 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4158` |
| `restoreNodeUnlocks(Map<Long, Long> existingUnlocks, RouteNodeMaps maps)` :4416 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4300` |
| `selectHiddenNodeIds(Long topicId)` :4513 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4287` |
| `restoreHiddenNodes(Set<Long> hiddenNodeIds, Map<Long, Long> oldToNew)` :4524 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4298` |
| `deleteTopicChapterFlow(Long topicId)` :4549 | TopicContentGraphAuthoringServiceImpl | `deleteTopicEditableChildren:4545`, `compileChapterFlow:4993` |
| `saveChaptersAndNodes(List<ChapterDTO> chapters, Long topicId, String username, Long memberId, boolean allowBundledDraft)` :4896 | TopicContentGraphAuthoringServiceImpl | `saveTopicChapters:4944` |
| `saveTopicChapters(List<ChapterDTO> chapters, Long topicId, String username, Long memberId, Integer productType, boolean chapterFlowV2, boolean allowBundledDraft)` :4935 | TopicContentGraphAuthoringServiceImpl | `createTopicInternal:3781`, `rebuildTopicContent:4295` |
| `shouldCompileChapterFlow(boolean chapterFlowV2, Integer productType, List<ChapterDTO> chapters)` :4947 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4289`, `saveTopicChapters:4937` |
| `compileChapterFlow(List<ChapterDTO> chapters, Long topicId, String username, Long memberId, boolean replaceExisting, boolean allowBundledDraft)` :4963 | TopicContentGraphAuthoringServiceImpl | `rebuildTopicContent:4291`, `saveTopicChapters:4938` |
| `assertLegacyChapterFlowWritable(Long topicId)` :5058 | TopicContentWriteGuard | `updateTopicByOwnerInternal:3912` |
| `assertChapterShapeForTopic(ChapterDTO chapter, Long topicId)` :5068 | TopicContentWriteGuard | `saveChaptersAndNodes:4909`, `compileChapterFlow:4971` |
| `assertChapterShape(ChapterDTO c)` :5076 | TopicContentWriteGuard |  |
| `assertChapterShape(ChapterDTO c, boolean clubDirect)` :5078 | TopicContentWriteGuard | `assertChapterShapeForTopic:5072`, `assertChapterShape:5076` |
| `assertAllowedValidationMethods(String raw)` :5120 | TopicContentWriteGuard | `assertChapterShape:5113` |
| `saveNodes(List<NodeDTO> nodes, Long topicId, Long chapterId, String username, Long memberId, RouteNodeMaps routeNodeMaps, boolean allowBundledDraft)` :5132 | TopicContentGraphAuthoringServiceImpl | `saveChaptersAndNodes:4928`, `saveNodes:5165` |
| `saveNodes(List<NodeDTO> nodes, Long topicId, Long chapterId, String username, Long memberId)` :5164 | TopicContentGraphAuthoringServiceImpl |  |
| `saveRouteDefinition(Long topicId, String requestedMode, String rawGraph, RouteNodeMaps maps, Long requestExpectedVersion, Long expectedVersion, Long targetVersion, Integer productType)` :5169 | TopicContentGraphAuthoringServiceImpl | `createTopicInternal:3785`, `rebuildTopicContent:4322` |
| `declaredRouteOutcomes(Long topicId)` :5205 | TopicRouteOutcomeReader | `normalizedBranchRouteForPublish:3376`, `saveRouteDefinition:5197` |
| `assertBranchGraphOnlyForOrienteering(String routeMode, Integer productType)` :5245 | TopicContentWriteGuard | `publishDraftInternal:2464`, `saveRouteDefinition:5181` |
| `containsNodeKey(com.fasterxml.jackson.databind.JsonNode array)` :5252 | TopicContentGraphAuthoringServiceImpl | `saveRouteDefinition:5192`, `saveRouteDefinition:5192` |
| `assertTemplateOwned(Long templateId, Long memberId, boolean allowBundledDraft)` :5453 | TopicContentWriteGuard | `compileChapterFlow:4979`, `saveNodes:5145` |
