package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit contract for G's derive -> capture -> validate publication phases.
 * Real reference collectors/validators, declaration service double; thread flags only model unit call context.
 * These tests do not establish MySQL RR, Spring propagation, bundle hash or G's compiler/freeze integration. */
class TopicPublicationDeclarationsTest {
    private final ThemeStateKeyService keys = mock(ThemeStateKeyService.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final List<CmsTopicNode> currentNodes = new ArrayList<>();
    private final List<CmsTopicChapter> currentChapters = new ArrayList<>();
    private final Map<Long,CmsMemberTemplate> currentTemplates = new LinkedHashMap<>();
    private TopicStateKeyRefService references;
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
    private final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
    private final TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
    private final com.chengyinhub.business.service.ITopicRouteRuntimeService runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
    private CmsTopic topic;
    private CmsMemberTemplate template;
    private boolean oldActive, oldReadOnly;
    private static final String OPTION = "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\","
            + "\"options\":[{\"id\":\"A\",\"label\":\"记住选择\",\"effects\":[{\"op\":\"ADD_TAG\",\"value\":\"tag.option\"}]}]}}";
    private static final String RUNTIME = "{\"schemaVersion\":1,\"currentScene\":\"one\",\"scenes\":[{\"id\":\"one\",\"name\":\"场景\","
            + "\"journeyStory\":{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"found\",\"title\":\"找到\","
            + "\"when\":[{\"var\":\"clue.current\",\"op\":\"GTE\",\"value\":1}]},{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}]}}}]}";

    @BeforeEach void fixture() {
        oldActive=TransactionSynchronizationManager.isActualTransactionActive();
        oldReadOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        topic=new CmsTopic(); topic.setId(7L);
        CmsTopicNode node=new CmsTopicNode();node.setId(11L);node.setTopicId(7L);node.setStatus(1L);node.setName("原地点");node.setTemplateId(101L);
        template=new CmsMemberTemplate();template.setId(101L);template.setAdvancedConfigJson(OPTION);
        currentNodes.add(node);currentTemplates.put(101L,template);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenAnswer(c -> topic);
        when(nodes.selectByTopicForUpdate(7L)).thenAnswer(c -> new ArrayList<>(currentNodes));
        when(chapters.selectByTopicForUpdate(7L)).thenAnswer(c -> new ArrayList<>(currentChapters));
        when(templates.selectCmsMemberTemplateByIdsForUpdate(anyList())).thenAnswer(c -> {
            List<Long> ids=c.getArgument(0);List<CmsMemberTemplate> rows=new ArrayList<>();
            for(Long id:ids)if(currentTemplates.containsKey(id))rows.add(currentTemplates.get(id));return rows;
        });
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(node));
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        when(templates.selectCmsMemberTemplateById(101L)).thenReturn(template);
        when(templates.selectCmsMemberTemplateByIds(anyList())).thenAnswer(c -> List.of(template));
        ReflectionTestUtils.setField(guard,"cmsTopicNodeMapper",nodes);
        ReflectionTestUtils.setField(guard,"cmsTopicChapterMapper",chapters);
        ReflectionTestUtils.setField(guard,"cmsMemberTemplateMapper",templates);
        ReflectionTestUtils.setField(guard,"npcProfileMapper",mock(NpcProfileMapper.class));
        references=spy(new TopicStateKeyRefService(nodes,templates,chapters));
        ReflectionTestUtils.setField(references,"cmsTopicMapper",topics);
        ReflectionTestUtils.setField(guard,"topicStateKeyRefService",references);
        ReflectionTestUtils.setField(guard,"themeStateKeyService",keys);
        ReflectionTestUtils.setField(guard,"topicRouteRuntimeService",runtime);
        ReflectionTestUtils.setField(guard,"routeOutcomeReader",new TopicRouteOutcomeReader(nodes,templates));
        when(keys.listCurrentDeclarations(7L)).thenReturn(Collections.emptyList());
        when(keys.listByTopic(7L)).thenReturn(Collections.emptyList());
    }
    @AfterEach void restoreContext() {
        TransactionSynchronizationManager.setActualTransactionActive(oldActive);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(oldReadOnly);
    }
    private ThemeStateKey declaration(String name) {
        ThemeStateKey row=new ThemeStateKey();row.setId(31L);row.setTopicId(7L);row.setStateKey(name);
        row.setKeyType(name.startsWith("tag.")?ThemeStateKey.TYPE_TAG:ThemeStateKey.TYPE_FLAG);
        row.setScope(ThemeStateKey.SCOPE_RUN);row.setLabel("原作者标签");return row;
    }
    private List<ThemeStateKey> captured() { return List.of(declaration("tag.option"),declaration("clue.current")); }

    @Test void everyFormalPortRejectsMissingOrReadOnlyTransactionBeforeReadingOrWritingDeclarations() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class,()->guard.deriveOptionTagsForPublication(topic));
        assertThrows(IllegalStateException.class,()->guard.assertStateKeysRegistered(topic,captured()));
        assertThrows(IllegalStateException.class,()->guard.assertThemeRuntimeReferences(topic,captured()));
        assertThrows(IllegalStateException.class,()->guard.normalizedBranchRouteForPublish(topic,captured()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class,()->guard.deriveOptionTagsForPublication(topic));
        assertThrows(IllegalStateException.class,()->guard.assertStateKeysRegistered(topic,captured()));
        assertThrows(IllegalStateException.class,()->guard.assertThemeRuntimeReferences(topic,captured()));
        assertThrows(IllegalStateException.class,()->guard.normalizedBranchRouteForPublish(topic,captured()));
        verifyNoInteractions(keys);
    }
    @Test void currentExistingTagIsNotUpsertedWhenOrdinaryRrProjectionIsStaleAndMissingIt() throws Exception {
        ThemeStateKey original=declaration("tag.option");original.setLabel("不能被默认 tag label 覆盖");
        original.setInitialValue(1);original.setVisible(false);original.setRulesJson("{\"levels\":[{\"at\":1,\"label\":\"原选择\"}]}");
        String before=new ObjectMapper().writeValueAsString(original);
        when(keys.listCurrentDeclarations(7L)).thenReturn(List.of(original));
        when(keys.listByTopic(7L)).thenReturn(Collections.emptyList());
        guard.deriveOptionTagsForPublication(topic);
        verify(keys).listCurrentDeclarations(7L);verify(keys,never()).listByTopic(any());verify(keys,never()).register(any(),any());
        assertEquals(before,new ObjectMapper().writeValueAsString(original));
    }
    @Test void missingTagUsesTheOriginalTagRunLabelMechanismBeforeFinalCapture() {
        guard.deriveOptionTagsForPublication(topic);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ThemeStateKey>> fresh=ArgumentCaptor.forClass(List.class);
        verify(keys).register(eq(7L),fresh.capture());assertEquals(1,fresh.getValue().size());
        ThemeStateKey row=fresh.getValue().get(0);assertEquals("tag.option",row.getStateKey());
        assertEquals(ThemeStateKey.TYPE_TAG,row.getKeyType());assertEquals(ThemeStateKey.SCOPE_RUN,row.getScope());
        assertEquals("tag.option",row.getLabel());verify(keys,never()).listByTopic(any());
    }
    @Test void bothFormalChecksUseTheCapturedProjectionWithoutDeclarationRereadsOrDerivation() {
        topic.setThemeRuntimeConfigJson(RUNTIME);
        when(keys.listByTopic(7L)).thenReturn(List.of(declaration("clue.foreign")));
        guard.assertStateKeysRegistered(topic,captured());guard.assertThemeRuntimeReferences(topic,captured());
        verifyNoInteractions(keys);
    }
    @Test void finalProjectionCannotBeRepairedByRereadingAnOrdinaryTagOrRegisteringAfterHash() {
        when(keys.listByTopic(7L)).thenReturn(List.of(declaration("tag.option")));
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertStateKeysRegistered(topic,List.of()));
        assertTrue(error.getMessage().contains("tag.option"));assertTrue(error.getMessage().contains("原地点"));
        verifyNoInteractions(keys);
    }
    @Test void derivingAnOptionTagDoesNotInventTheOriginalMissingCounterDeclaration() {
        template.setAdvancedConfigJson("{\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.option\"},{\"var\":\"counter.coins\",\"op\":\"GTE\",\"value\":1}]}");
        guard.deriveOptionTagsForPublication(topic);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ThemeStateKey>> fresh=ArgumentCaptor.forClass(List.class);
        verify(keys).register(eq(7L),fresh.capture());assertEquals(List.of("tag.option"),fresh.getValue().stream().map(ThemeStateKey::getStateKey).toList());
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertStateKeysRegistered(topic,List.of(declaration("tag.option"))));
        assertTrue(error.getMessage().contains("counter.coins"));verify(keys,never()).listByTopic(any());
        verify(keys,times(1)).register(any(),any());
    }
    @Test void ordinaryChecksRemainReadOnlyWithoutTransactionOrCurrentLocks() {
        TransactionSynchronizationManager.setActualTransactionActive(false);topic.setThemeRuntimeConfigJson(RUNTIME);
        when(keys.listByTopic(7L)).thenReturn(captured());
        guard.assertStateKeysRegistered(topic);guard.assertThemeRuntimeReferences(topic);
        verify(keys,times(2)).listByTopic(7L);verify(keys,never()).listCurrentDeclarations(any());verify(keys,never()).register(any(),any());
    }
    @Test void ordinaryCheckAcceptsLegitimateUnpersistedOptionTagWithoutAPreviewSideEffect() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertDoesNotThrow(()->guard.assertStateKeysRegistered(topic));
        verify(keys,never()).register(any(),any());verify(keys,never()).listCurrentDeclarations(any());
    }
    @Test void ordinaryPreviewStillRejectsUnknownCounterInsteadOfInventingADefinition() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        template.setAdvancedConfigJson("{\"requires\":[{\"var\":\"counter.missing\",\"op\":\"GTE\",\"value\":1}]}");
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertStateKeysRegistered(topic));
        assertTrue(error.getMessage().contains("counter.missing"));verify(keys,never()).register(any(),any());verify(keys,never()).listCurrentDeclarations(any());
    }
    @Test void nullCrossTopicAndDuplicateFinalProjectionsFailClosedBeforeReferenceChecks() {
        assertThrows(IllegalStateException.class,()->guard.assertStateKeysRegistered(topic,(List<ThemeStateKey>)null));
        assertThrows(IllegalStateException.class,()->guard.assertThemeRuntimeReferences(topic,(List<ThemeStateKey>)null));
        ThemeStateKey wrong=declaration("tag.option");wrong.setTopicId(8L);
        for(List<ThemeStateKey> invalid:List.of(List.of(wrong),Arrays.asList((ThemeStateKey)null),List.of(declaration("tag.option"),declaration("tag.option")))) {
            assertThrows(IllegalStateException.class,()->guard.assertStateKeysRegistered(topic,invalid));
            assertThrows(IllegalStateException.class,()->guard.assertThemeRuntimeReferences(topic,invalid));
        }
        verifyNoInteractions(keys);
    }
    @Test void checksDoNotMutateAnyCallerOwnedDeclarationMetadataOrRuntimeInput() throws Exception {
        List<ThemeStateKey> frozen=captured();topic.setThemeRuntimeConfigJson(RUNTIME);
        frozen.get(0).setCreateTime(new Date(1234));frozen.get(0).setUpdateTime(new Date(5678));
        String before=new ObjectMapper().writeValueAsString(frozen), runtime=topic.getThemeRuntimeConfigJson();
        guard.assertStateKeysRegistered(topic,frozen);guard.assertThemeRuntimeReferences(topic,frozen);
        assertEquals(before,new ObjectMapper().writeValueAsString(frozen));assertEquals(runtime,topic.getThemeRuntimeConfigJson());verifyNoInteractions(keys);
    }
    @Test void currentReadStorageFailureIsNotEmptyDeclarationOrAnAutomaticRegistration() {
        DataAccessResourceFailureException unavailable=new DataAccessResourceFailureException("controlled declaration read failure");
        when(keys.listCurrentDeclarations(7L)).thenThrow(unavailable);
        assertSame(unavailable,assertThrows(DataAccessResourceFailureException.class,()->guard.deriveOptionTagsForPublication(topic)));
        verify(keys,never()).register(any(),any());verify(keys,never()).listByTopic(any());
    }
    @Test void formalProducerRequiresTheRealReferenceCollectorDependency() {
        ReflectionTestUtils.setField(guard,"topicStateKeyRefService",null);
        assertThrows(IllegalStateException.class,()->guard.deriveOptionTagsForPublication(topic));verifyNoInteractions(keys);
    }
    @Test void malformedReferenceKeepsTheOriginalDiagnosticInBothPhases() {
        template.setAdvancedConfigJson("{");
        for(Runnable phase:List.<Runnable>of(()->guard.deriveOptionTagsForPublication(topic),()->guard.assertStateKeysRegistered(topic,List.of()))) {
            ServiceException error=assertThrows(ServiceException.class,phase::run);
            assertTrue(error.getMessage().contains("玩法配置无法解析"));
            assertTrue(error.getMessage().contains("topicId=7, path=nodes[nodeId=11].template.advancedConfigJson"));
        }
        verify(keys,never()).register(any(),any());verify(keys,never()).listByTopic(any());
    }
    @Test void runtimeReferencesDoNotConsultLiveDeclarationsToRepairAMissingCapturedKey() {
        topic.setThemeRuntimeConfigJson(RUNTIME);when(keys.listByTopic(7L)).thenReturn(captured());
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->guard.assertThemeRuntimeReferences(topic,List.of(declaration("tag.option"))));
        assertTrue(error.getMessage().contains("剧情状态未注册"));assertTrue(error.getMessage().contains("path=themeRuntimeConfigJson"));assertNotNull(error.getCause());
        verifyNoInteractions(keys);
    }
    @Test void tagReferencesFromDifferentOriginalSourcesAreStillDerivedOnlyOnce() {
        topic.setJourneyStoryJson("{\"schemaVersion\":1,\"ending\":{\"endings\":[{\"code\":\"chosen\",\"title\":\"选择\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.option\"}]},{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}]}}");
        guard.deriveOptionTagsForPublication(topic);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ThemeStateKey>> fresh=ArgumentCaptor.forClass(List.class);
        verify(keys).register(eq(7L),fresh.capture());assertEquals(1,fresh.getValue().size());assertEquals("tag.option",fresh.getValue().get(0).getStateKey());
    }
    @Test void readonlyPreviewAcceptsOriginalGeneratedClueNamesWithoutPersistingThem() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        String generated = "clue.h_123456abcdef";
        for (boolean named : new boolean[]{false,true}) {
            // Both forms are derived by G's original collectTemplateClues: a var reference or a names entry.
            template.setAdvancedConfigJson("{\"schemaVersion\":1,"
                    + (named ? "\"clues\":{\"" + generated + "\":\"原线索\"}," : "")
                    + "\"requires\":[{\"var\":\"" + generated + "\",\"op\":\"GTE\",\"value\":1}]}");
            topic.setThemeRuntimeConfigJson(RUNTIME.replace("clue.current",generated));
            assertDoesNotThrow(()->guard.assertStateKeysRegistered(topic));
            assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic));
        }
        verify(keys,never()).listCurrentDeclarations(any());verify(keys,never()).register(any(),any());
    }
    @Test void formalChecksNeverTreatMissingCapturedGeneratedClueAsAlreadyRegistered() {
        String generated="clue.h_123456abcdef";
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"requires\":[{\"var\":\""+generated+"\",\"op\":\"GTE\",\"value\":1}]}");
        topic.setThemeRuntimeConfigJson(RUNTIME.replace("clue.current",generated));
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertStateKeysRegistered(topic,List.of()));
        assertTrue(error.getMessage().contains(generated));
        assertThrows(IllegalArgumentException.class,()->guard.assertThemeRuntimeReferences(topic,List.of()));
        verifyNoInteractions(keys);
    }
    @Test void arbitraryClueNamesStillRequireADeclarationEvenWhenAMapPretendsTheyAreGenerated() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"clues\":{\"clue.page\":\"未声明\"},\"requires\":[{\"var\":\"clue.page\",\"op\":\"GTE\",\"value\":1}]}");
        topic.setThemeRuntimeConfigJson(RUNTIME.replace("clue.current","clue.page"));
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertStateKeysRegistered(topic));
        assertTrue(error.getMessage().contains("clue.page"));
        assertThrows(IllegalArgumentException.class,()->guard.assertThemeRuntimeReferences(topic));
        verify(keys,never()).listCurrentDeclarations(any());verify(keys,never()).register(any(),any());
    }
    @Test void ordinaryCreationCheckDoesNotImplicitlySwitchToPublishingBecauseATransactionIsActive() {
        // createTopicWithChapters also calls the original single-argument runtime-reference check before compilation.
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        String generated="clue.h_123456abcdef";
        template.setAdvancedConfigJson(OPTION.substring(0,OPTION.length()-1)+",\"clues\":{\""+generated+"\":\"原线索\"}}");
        topic.setThemeRuntimeConfigJson(RUNTIME.replace("clue.current",generated));
        assertDoesNotThrow(()->guard.assertStateKeysRegistered(topic));
        assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic));
        verify(keys,times(2)).listByTopic(7L);verify(keys,never()).listCurrentDeclarations(any());verify(keys,never()).register(any(),any());
    }

    @Test void realPrecheckCallerAcceptsDerivedCluesButStillReportsUnknownCluesWithoutWrites() {
        TransactionSynchronizationManager.setActualTransactionActive(false);topic.setPublishMode("simple");
        TopicBundleReviewService bundle=mock(TopicBundleReviewService.class);
        TopicPublishCheckService preview=new TopicPublishCheckService(nodes,templates,keys,
                new TopicStateKeyRefService(nodes,templates,chapters),bundle);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"clues\":{\"clue.h_123456abcdef\":\"原线索\"},\"requires\":[{\"var\":\"clue.h_123456abcdef\",\"op\":\"GTE\",\"value\":1}]}");
        var legal = preview.check(topic);
        assertTrue(legal.stream().noneMatch(issue->TopicPublishCheckService.PublishCheckIssue.LEVEL_ERROR.equals(issue.getLevel())),
                () -> legal.stream().map(TopicPublishCheckService.PublishCheckIssue::getMessage).toList().toString());
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"requires\":[{\"var\":\"clue.missing\",\"op\":\"GTE\",\"value\":1}]}");
        assertTrue(preview.check(topic).stream().anyMatch(issue->TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED.equals(issue.getCode())));
        verify(keys,never()).register(any(),any());verify(keys,never()).listCurrentDeclarations(any());verifyNoInteractions(bundle);
    }

    @Test void ordinaryLinearOptionTemplateAlwaysUsesTheCompleteFreezePortWithoutEnablingRuntimeFeatures() {
        topic.setRouteMode("LINEAR");template.setAdvancedConfigJson(null);template.setValidationMethod(3); // Existing option verification.
        List<ThemeStateKey> declarations=captured();String originalTheme=topic.getThemeRuntimeConfigJson(),originalRules=topic.getJourneyRulesJson();
        assertFalse(guard.requiresFullRuntimeSnapshot(topic));
        when(runtime.freezePublication(eq(topic),isNull(),eq(declarations))).thenReturn("opaque complete-content result");
        assertEquals("opaque complete-content result",guard.normalizedBranchRouteForPublish(topic,declarations));
        verify(runtime).freezePublication(eq(topic),isNull(),eq(declarations));
        verify(runtime,never()).compileTemplateRules(any());verify(runtime,never()).freezePublication(any(),any());
        assertFalse(guard.requiresFullRuntimeSnapshot(topic));assertEquals(originalTheme,topic.getThemeRuntimeConfigJson());assertEquals(originalRules,topic.getJourneyRulesJson());
        verifyNoInteractions(keys);
    }
    @Test void linearNodeWithoutTemplateStillFreezesOriginalContentAndSameDeclarations() {
        CmsTopicNode bare=new CmsTopicNode();bare.setId(11L);bare.setTopicId(7L);bare.setStatus(1L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(bare));topic.setRouteMode("LINEAR");
        when(runtime.freezePublication(eq(topic),isNull(),eq(List.of()))).thenReturn("opaque no-template content result");
        assertFalse(guard.requiresFullRuntimeSnapshot(topic));
        assertEquals("opaque no-template content result",guard.normalizedBranchRouteForPublish(topic,List.of()));
        verify(runtime).freezePublication(eq(topic),isNull(),eq(List.of()));
        verify(runtime,never()).compileTemplateRules(any());assertFalse(guard.requiresFullRuntimeSnapshot(topic));verifyNoInteractions(keys);
    }
    @Test void oldSingleArgumentLinearPreviewKeepsItsExistingNullProjectionAndDoesNotCallFormalFreeze() {
        TransactionSynchronizationManager.setActualTransactionActive(false);topic.setRouteMode("LINEAR");
        assertFalse(guard.requiresFullRuntimeSnapshot(topic));assertNull(guard.normalizedBranchRouteForPublish(topic));
        verify(runtime).compileTemplateRules(topic);verify(runtime,never()).freezePublication(any(),any());
        verify(runtime,never()).freezePublication(any(),any(),any());verifyNoInteractions(keys);
    }
    @Test void formalFreezeCannotReturnNullOrBlankAsAnOrdinaryQuestionSuccess() {
        topic.setRouteMode("LINEAR");
        for(String missing:new String[]{null,"","  "}) {
            when(runtime.freezePublication(eq(topic),isNull(),eq(List.of()))).thenReturn(missing);
            IllegalStateException error=assertThrows(IllegalStateException.class,()->guard.normalizedBranchRouteForPublish(topic,List.of()));
            assertEquals("主题发布内容快照为空",error.getMessage());
        }
        assertFalse(guard.requiresFullRuntimeSnapshot(topic));verify(runtime,never()).compileTemplateRules(any());verifyNoInteractions(keys);
    }
    @Test void formalBranchReusesTheExistingDiagnosticBeforeCallingCompleteFreeze() {
        CmsTopicNode terminal=new CmsTopicNode();terminal.setId(12L);terminal.setTopicId(7L);terminal.setStatus(1L);
        CmsTopicNode start=new CmsTopicNode();start.setId(11L);start.setTopicId(7L);start.setStatus(1L);start.setTemplateId(101L);
        currentNodes.clear(); currentNodes.addAll(List.of(start,terminal));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(List.of(start,terminal));topic.setRouteMode("BRANCH_GRAPH");
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":11,\"terminalNodeIds\":[12],\"edges\":[{\"id\":\"go\",\"fromNodeId\":11,\"toNodeId\":12,\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"},\"conditions\":[{\"op\":\"GT\",\"var\":\"missing\",\"value\":0}]}]}");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->guard.normalizedBranchRouteForPublish(topic,List.of()));
        assertTrue(error.getMessage().contains("使用未声明变量"), error.getMessage());
        assertTrue(error.getMessage().contains("topicId=7, path=routeGraphJson"), error.getMessage());assertNotNull(error.getCause());
        verify(runtime,never()).freezePublication(any(),any(),any());verify(runtime,never()).compileTemplateRules(any());verifyNoInteractions(keys);
    }

    @Test void formalTagDerivationUsesPublicationCollectorRatherThanOldOrdinaryReferenceView() {
        TopicStateKeyRefService collector=mock(TopicStateKeyRefService.class);
        TopicStateKeyRefService.RefCollection current=new TopicStateKeyRefService.RefCollection();
        current.getRefsByNode().put(11L,Set.of("tag.current"));current.getNodeLabels().put(11L,"当前地点");
        when(collector.collectForPublication(topic)).thenReturn(current);
        when(collector.collect(topic)).thenThrow(new AssertionError("formal derive used ordinary RR references"));
        ReflectionTestUtils.setField(guard,"topicStateKeyRefService",collector);
        guard.deriveOptionTagsForPublication(topic);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<ThemeStateKey>> fresh=ArgumentCaptor.forClass(List.class);
        verify(keys).register(eq(7L),fresh.capture());assertEquals(List.of("tag.current"),fresh.getValue().stream().map(ThemeStateKey::getStateKey).toList());
        verify(collector).collectForPublication(topic);verify(collector,never()).collect(any());
    }
    @Test void formalRegisteredKeyCheckUsesPublicationReferencesWithoutOrdinaryCollectorFallback() {
        TopicStateKeyRefService collector=mock(TopicStateKeyRefService.class);
        TopicStateKeyRefService.RefCollection current=new TopicStateKeyRefService.RefCollection();
        current.getRefsByNode().put(11L,Set.of("tag.current"));current.getNodeLabels().put(11L,"当前地点");
        when(collector.collectForPublication(topic)).thenReturn(current);
        when(collector.collect(topic)).thenThrow(new AssertionError("formal key check used ordinary RR references"));
        ReflectionTestUtils.setField(guard,"topicStateKeyRefService",collector);
        assertDoesNotThrow(()->guard.assertStateKeysRegistered(topic,List.of(declaration("tag.current"))));
        verify(collector).collectForPublication(topic);verify(collector,never()).collect(any());verifyNoInteractions(keys);
    }
    @Test void ordinaryCheckKeepsItsOrdinaryCollectorEvenInsideAWritableTransaction() {
        TopicStateKeyRefService collector=mock(TopicStateKeyRefService.class);
        TopicStateKeyRefService.RefCollection ordinary=new TopicStateKeyRefService.RefCollection();
        ordinary.getRefsByNode().put(11L,Set.of("tag.preview"));ordinary.getNodeLabels().put(11L,"预检查地点");
        when(collector.collect(topic)).thenReturn(ordinary);
        when(collector.collectForPublication(topic)).thenThrow(new AssertionError("preview entered publication current-read port"));
        ReflectionTestUtils.setField(guard,"topicStateKeyRefService",collector);
        assertDoesNotThrow(()->guard.assertStateKeysRegistered(topic));
        verify(collector).collect(topic);verify(collector,never()).collectForPublication(any());
        verify(keys,never()).register(any(),any());verify(keys,never()).listCurrentDeclarations(any());
    }

    private com.chengyinhub.business.service.ISmsCouponService couponOwner(long id,long owner) {
        var lookup=mock(com.chengyinhub.business.service.ISmsCouponService.class);
        SmsCoupon coupon=new SmsCoupon();coupon.setId(id);coupon.setMemberId(owner);coupon.setDelFlag(0);
        when(lookup.selectSmsCouponById(id)).thenReturn(coupon);
        var authorization=new com.chengyinhub.business.service.impl.CouponRewardAuthorizationServiceImpl();
        ReflectionTestUtils.setField(authorization,"smsCouponService",lookup);
        ReflectionTestUtils.setField(guard,"couponRewardAuthorizationService",authorization);return lookup;
    }
    @Test void formalOrdinaryTopicRejectsForeignConditionalRewardBeforeTheNoRuntimeEarlyReturn() {
        template.setMemberId(7L);template.setRewardRulesJson("[{\"outcomeCode\":\"COMPLETED\",\"couponId\":401}]");
        var lookup=couponOwner(401L,99L);forbidOrdinaryChildReads();
        assertThrows(IllegalArgumentException.class,()->guard.assertThemeRuntimeReferences(topic,captured()));
        verify(lookup).selectSmsCouponById(401L);verify(references,times(1)).currentForPublication(topic);verifyNoInteractions(keys);
        assertNull(topic.getThemeRuntimeConfigJson());
    }
    @Test void formalRewardScopeUsesTheCurrentTemplateOwnerAndDoesNotEnableRuntimeForOrdinaryQuestions() {
        topic.setMemberId(99L);template.setMemberId(7L);template.setCouponId(401L);
        template.setRewardRulesJson("[{\"outcomeCode\":\"COMPLETED\",\"couponId\":401}]");
        var lookup=couponOwner(401L,7L);forbidOrdinaryChildReads();
        assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic,captured()));
        verify(lookup,times(1)).selectSmsCouponById(401L);verify(references,times(1)).currentForPublication(topic);verifyNoInteractions(keys);
        assertNull(topic.getThemeRuntimeConfigJson());
    }
    @Test void ordinaryReadonlyPreviewDoesNotGainTheFormalCouponLookupOrWrite() {
        template.setMemberId(7L);template.setCouponId(401L);var lookup=couponOwner(401L,99L);
        assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic));
        verifyNoInteractions(lookup,keys);verify(references,never()).currentForPublication(any());
    }

    private void forbidOrdinaryChildReads() {
        doThrow(new AssertionError("formal references used ordinary node snapshot")).when(nodes).selectCmsTopicNodeList(any());
        doThrow(new AssertionError("formal references used ordinary chapter snapshot")).when(chapters).selectCmsTopicChapterList(any());
        doThrow(new AssertionError("formal references used ordinary template snapshot")).when(templates).selectCmsMemberTemplateById(anyLong());
        doThrow(new AssertionError("formal references used ordinary template batch snapshot")).when(templates).selectCmsMemberTemplateByIds(anyList());
    }
    @Test void formalRuntimeReferencesUseLockedTopicAndTemplateProjectionRatherThanStaleCallerOrOrdinaryReads() {
        CmsTopic locked=new CmsTopic();locked.setId(7L);locked.setThemeRuntimeConfigJson(RUNTIME);
        when(topics.selectCmsTopicByIdForUpdate(7L)).thenReturn(locked);
        topic.setThemeRuntimeConfigJson("{"); // A stale ordinary caller bean must not drive formal validation.
        forbidOrdinaryChildReads();
        assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic,captured()));
        verify(references,times(1)).currentForPublication(topic);
        verify(references,never()).collect(any());verify(references,never()).collectForPublication(any());
        verify(nodes,times(1)).selectByTopicForUpdate(7L);verify(chapters,times(1)).selectByTopicForUpdate(7L);
        verify(templates,times(1)).selectCmsMemberTemplateByIdsForUpdate(anyList());
        assertEquals("{",topic.getThemeRuntimeConfigJson());verifyNoInteractions(keys);
    }
    @Test void nestedSceneAndChapterBudgetReuseOneCurrentProjectionIncludingNpcNodeScope() {
        CmsTopicChapter chapter=new CmsTopicChapter();chapter.setId(22L);chapter.setTopicId(7L);currentChapters.add(chapter);
        currentNodes.get(0).setChapterId(22L);
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"capacity\":0,\"chapters\":[{\"index\":0,\"hpCost\":0,\"luckCost\":0}],\"currentScene\":\"one\",\"scenes\":[{\"id\":\"one\",\"name\":\"场景\",\"journeyStory\":{\"schemaVersion\":1,\"openingChapterId\":22}}]}");
        forbidOrdinaryChildReads();
        assertDoesNotThrow(()->guard.assertThemeRuntimeReferences(topic,captured()));
        verify(references,times(1)).currentForPublication(topic);verify(references,never()).collect(any());
        verify(chapters,times(1)).selectByTopicForUpdate(7L);verify(nodes,times(1)).selectByTopicForUpdate(7L);
        verify(templates,times(1)).selectCmsMemberTemplateByIdsForUpdate(anyList());verifyNoInteractions(keys);
    }
    @Test void nestedJourneyStillRejectsInactiveDispositionNodeWithOriginalSceneDiagnostic() {
        CmsTopicNode inactive=new CmsTopicNode();inactive.setId(12L);inactive.setTopicId(7L);inactive.setStatus(0L);inactive.setTemplateId(201L);currentNodes.add(inactive);
        CmsMemberTemplate branch=new CmsMemberTemplate();branch.setId(201L);branch.setAdvancedConfigJson("{\"schemaVersion\":1,\"branch\":{\"enabled\":true}}");currentTemplates.put(201L,branch);
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"currentScene\":\"one\",\"scenes\":[{\"id\":\"one\",\"name\":\"场景\",\"journeyStory\":{\"schemaVersion\":1,\"ending\":{\"dispositionNodeId\":12,\"endings\":[{\"code\":\"selected\",\"title\":\"原处置结局\",\"when\":[{\"var\":\"sys.outcome.12\",\"op\":\"EQ\",\"value\":1}]},{\"code\":\"default\",\"title\":\"默认\",\"fallback\":true}]}}}]}");
        forbidOrdinaryChildReads();
        ServiceException error=assertThrows(ServiceException.class,()->guard.assertThemeRuntimeReferences(topic,captured()));
        assertTrue(error.getMessage().contains("dispositionNodeId"), error.getMessage());
        assertTrue(error.getMessage().contains("path=themeRuntimeConfigJson.scenes[0].journeyStory"), error.getMessage());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        verify(references,times(1)).currentForPublication(topic);verify(references,never()).collect(any());verifyNoInteractions(keys);
    }

}
