package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicPublishCheckService;
import com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.chengyinhub.business.service.support.TopicBundleReviewService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * R8 发布前试检查:坏键 / 坏配置 / 坏数值规则 / 线性建局说明各自出项,好主题不出项。
 * 与发布闸共用同一份引用收集(见 TopicStateKeyRefService),这里钉的是「列出来」而不是「拦下来」。
 */
class TopicPublishCheckServiceTest {

    private static final Long TOPIC_ID = 21L;
    private static final Long NODE_ID = 301L;
    private static final Long TEMPLATE_ID = 401L;
    /** 一份合法、未启用任何旅程机制的块配置。 */
    private static final String PLAIN_CONFIG = "{\"schemaVersion\":1,"
            + "\"timer\":{\"enabled\":true,\"durationSeconds\":60,\"timeoutResult\":\"FAILED\"}}";
    /** 一份合法、带尾声 relationKey 的剧情配置(R17-FIX 类型闸用)。 */
    private static final String STORY_WITH_EPILOGUE = "{\"schemaVersion\":1,"
            + "\"ending\":{\"endings\":[{\"code\":\"b\",\"title\":\"B\",\"fallback\":true}],"
            + "\"epilogues\":[{\"npcId\":7,\"relationKey\":\"relation.arong\","
            + "\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]}}";

    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private ThemeStateKeyMapper keyMapper;
    private TopicPublishCheckService service;
    private TopicBundleReviewService bundleService;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        bundleService = mock(TopicBundleReviewService.class);
        service = new TopicPublishCheckService(nodeMapper, templateMapper,
                new ThemeStateKeyService(keyMapper),
                new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper), bundleService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"pro", "PRO"})
    void professionalPrecheckReportsActualBundleReadinessFailure(String mode) {
        CmsTopic topic = branchTopic();
        topic.setMemberId(7L);
        topic.setPublishMode(mode);
        when(bundleService.freezeForSubmission(TOPIC_ID, 7L))
                .thenThrow(new IllegalArgumentException("missing chapter and node"));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size());
        assertEquals("TOPIC_BUNDLE_INVALID", issues.get(0).getCode());
        assertEquals("error", issues.get(0).getLevel());
        assertEquals("missing chapter and node", issues.get(0).getMessage());
        verify(bundleService).freezeForSubmission(TOPIC_ID, 7L);
    }

    @Test
    void bundleStateFailurePreservesExistingUnregisteredKeyIssues() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"var\":\"counter.missing\",\"op\":\"EQ\",\"value\":1}]}");
        CmsTopic topic = branchTopic(node("room"));
        topic.setMemberId(7L);
        topic.setPublishMode("pro");
        when(bundleService.freezeForSubmission(TOPIC_ID, 7L))
                .thenThrow(new IllegalStateException("template unavailable"));
        List<PublishCheckIssue> issues = service.check(topic);
        assertTrue(issues.stream().anyMatch(x -> "STATE_KEY_UNREGISTERED".equals(x.getCode())));
        assertTrue(issues.stream().anyMatch(x -> "TOPIC_BUNDLE_INVALID".equals(x.getCode())
                && "template unavailable".equals(x.getMessage())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "AI_SIMPLE"})
    void simpleModeDoesNotAcquireProfessionalBundleRequirements(String mode) {
        CmsTopic topic = branchTopic();
        topic.setMemberId(7L);
        topic.setPublishMode(mode);
        assertTrue(service.check(topic).isEmpty());
        verify(bundleService, never()).freezeForSubmission(TOPIC_ID, 7L);
    }

    @Test
    void readyProfessionalPrecheckUsesReadonlyFreezeWithoutIssue() {
        CmsTopic topic = branchTopic();
        topic.setMemberId(7L);
        topic.setPublishMode("pro");
        assertTrue(service.check(topic).isEmpty());
        verify(bundleService).freezeForSubmission(TOPIC_ID, 7L);
    }

    @Test
    void healthyTopicChecksClean() {
        stubTemplate(PLAIN_CONFIG);
        CmsTopic topic = branchTopic(node("书房"));

        assertTrue(service.check(topic).isEmpty(), "没有任何引用与坏配置时,问题列表必须是空的");
    }

    @Test
    void registeredKeyInBranchTopicChecksClean() {
        stubTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}]}");
        CmsTopic topic = branchTopic(node("书房"));
        stubRegistered("clue.ledger_page");

        assertTrue(service.check(topic).isEmpty());
    }

    @Test
    void unregisteredKeyReportedWithNodeIdAndPosition() {
        stubTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"var\":\"clue.ledger_page\",\"op\":\"EQ\",\"value\":1}],"
                + "\"effects\":[{\"op\":\"INC\",\"var\":\"counter.coins\",\"value\":2}]}");
        CmsTopic topic = branchTopic(node("书房"));
        stubRegistered("clue.ledger_page");

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        PublishCheckIssue issue = issues.get(0);
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issue.getCode());
        assertEquals("error", issue.getLevel());
        assertEquals(NODE_ID, issue.getNodeId());
        assertTrue(issue.getMessage().contains("counter.coins"), issue.getMessage());
        assertTrue(issue.getMessage().contains("书房"), issue.getMessage());
    }

    /**
     * R22-SEC S16:试检查与发布闸同口径,提前报出 NODE_COMPLETED 的归属问题(而不是等到发布才拦)。
     *
     * <p>负控:去掉 collectBlockConfigIssues 里的 assertCompletedNodeIdsOwned 调用,本用例红。
     */
    @Test
    void nodeCompletedOutsideThemeReportedByPreCheck() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");
        CmsTopic topic = branchTopic(node("书房"));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        PublishCheckIssue issue = issues.get(0);
        assertEquals(TopicPublishCheckService.CODE_BLOCK_CONFIG_INVALID, issue.getCode());
        assertEquals(NODE_ID, issue.getNodeId());
        assertTrue(issue.getMessage().contains("NODE_COMPLETED"), issue.getMessage());
        assertTrue(issue.getMessage().contains("9999"), issue.getMessage());
    }

    /** 同一个键被节点与主题数值规则同时引用时,引用位置都要列出来,不能只剩一边。 */
    @Test
    void keyReferencedByNodeAndJourneyRulesListsBothPositions() {
        stubTemplate("{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.injured\"}]}");
        CmsTopic topic = branchTopic(node("书房"));
        topic.setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("节点「书房」"), issues.get(0).getMessage());
        assertTrue(issues.get(0).getMessage().contains("主题数值规则"), issues.get(0).getMessage());
    }

    @Test
    void invalidBlockConfigReportedWithValidatorMessage() {
        stubTemplate("{\"schemaVersion\":1,\"blindTaste\":{\"enabled\":true,\"title\":\"盲品\","
                + "\"options\":[{\"key\":\"a\",\"label\":\"甲\"}],\"answerKey\":\"a\"}}");
        CmsTopic topic = branchTopic(node("厨房"));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        PublishCheckIssue issue = issues.get(0);
        assertEquals(TopicPublishCheckService.CODE_BLOCK_CONFIG_INVALID, issue.getCode());
        assertEquals("error", issue.getLevel());
        assertEquals(NODE_ID, issue.getNodeId());
        assertTrue(issue.getMessage().contains("盲品选项须为 2 至 6 项"), issue.getMessage());
    }

    /** 坏 JSON 只报一条(校验器与引用收集同一句「不是合法 JSON」,不许重复出项)。 */
    @Test
    void unparsableBlockJsonReportedOnce() {
        stubTemplate("{broken");
        CmsTopic topic = branchTopic(node("书房"));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_BLOCK_CONFIG_INVALID, issues.get(0).getCode());
        assertEquals(NODE_ID, issues.get(0).getNodeId());
        assertTrue(issues.get(0).getMessage().contains("不是合法 JSON"), issues.get(0).getMessage());
    }

    @Test
    void invalidJourneyRulesReported() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson("{broken");

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_JOURNEY_RULES_INVALID, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("旅程"), issues.get(0).getMessage());
    }

    @Test
    void journeyRulesExhaustTagUnregisteredReportedAsStateKey() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(
                "{\"schemaVersion\":1,\"checkEnabled\":true,\"recovery\":{\"exhaustTag\":\"tag.injured\"}}");

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("tag.injured"), issues.get(0).getMessage());
        assertTrue(issues.get(0).getMessage().contains("主题数值规则"), issues.get(0).getMessage());
    }

    /**
     * R19:章节故事流 beat 块 when 引用的未注册键,试检查必须列为 error ——
     * 与发布闸同一份收集,不能只有发布时才发现。
     *
     * <p>负控:摘掉 TopicStateKeyRefService.collectChapters 的 chapterKeys.addAll,本用例立刻红。
     */
    @Test
    void chapterStoryFlowUnregisteredKeyReportedAsStateKey() {
        CmsTopic topic = branchTopic();
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"revisit\",\"field\":\"change\",\"nodeId\":301,"
                + "\"content\":\"门边展签已更正\","
                + "\"when\":{\"var\":\"counter.stage1_result\",\"op\":\"GTE\",\"value\":1}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        PublishCheckIssue issue = issues.get(0);
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issue.getCode());
        assertEquals("error", issue.getLevel());
        assertNull(issue.getNodeId(), "章节故事流的键不挂在节点上");
        assertTrue(issue.getMessage().contains("counter.stage1_result"), issue.getMessage());
        assertTrue(issue.getMessage().contains("章节故事流"), issue.getMessage());
    }

    @Test
    void linearTopicWithJourneyRulesWarnsAboutSession() {
        stubTemplate(PLAIN_CONFIG);
        CmsTopic topic = linearTopic(node("书房"));
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_LINEAR_JOURNEY_SESSION, issues.get(0).getCode());
        assertEquals("warn", issues.get(0).getLevel());
    }

    /** 建局条件与运行期一致:没有启用节点就没有局,不许对空主题喊建局。 */
    @Test
    void linearTopicWithoutEnabledNodesHasNoSessionWarn() {
        CmsTopic topic = linearTopic();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");

        assertTrue(service.check(topic).isEmpty(), "空主题不该出建局 warn");
    }

    /** 分支主题本来就走路线图建局,不重复提醒。 */
    @Test
    void branchTopicWithJourneyRulesHasNoModeWarn() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"checkEnabled\":true}");

        assertTrue(service.check(topic).isEmpty(), "分支主题不该出建局说明");
    }

    /** 老线性主题(无任何旅程配置)不建局,也不出 warn。 */
    @Test
    void legacyLinearTopicWithoutJourneyConfigHasNoWarn() {
        stubTemplate(PLAIN_CONFIG);
        CmsTopic topic = linearTopic(node("书房"));

        List<PublishCheckIssue> issues = service.check(topic);

        assertTrue(issues.isEmpty(), issues.toString());
    }

    // ---------- R17-FIX:尾声 relationKey 的未注册 / 类型闸也要在试检查里预演 ----------

    @Test
    void unregisteredEpilogueRelationKeyReportedAsStateKey() {
        CmsTopic topic = branchTopic();
        topic.setJourneyStoryJson(STORY_WITH_EPILOGUE);

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("relation.arong"), issues.get(0).getMessage());
        assertTrue(issues.get(0).getMessage().contains("主题剧情配置"), issues.get(0).getMessage());
    }

    /** 注册了但类型不是 relation:发布闸会拦,试检查必须同样报出(不能只报未注册)。 */
    @Test
    void epilogueRelationKeyRegisteredAsWrongTypeReported() {
        CmsTopic topic = branchTopic();
        topic.setJourneyStoryJson(STORY_WITH_EPILOGUE);
        stubRegisteredTyped("relation.arong", ThemeStateKey.TYPE_TAG);

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_TYPE_INVALID, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("relation.arong"), issues.get(0).getMessage());
        assertTrue(issues.get(0).getMessage().contains("必须是 relation"), issues.get(0).getMessage());
    }

    @Test
    void epilogueRelationKeyRegisteredAsRelationChecksClean() {
        CmsTopic topic = branchTopic();
        topic.setJourneyStoryJson(STORY_WITH_EPILOGUE);
        stubRegisteredTyped("relation.arong", ThemeStateKey.TYPE_RELATION);

        assertTrue(service.check(topic).isEmpty());
    }


    @Test
    void declaredThoughtDoneChecksCleanWithoutRegistryWrites() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        topic.setJourneyStoryJson(thoughtEnding("thought.window.done"));

        assertTrue(service.check(topic).isEmpty(), "本主题已声明的运行期标签不应要求普通状态键登记");
        org.mockito.Mockito.verify(keyMapper, org.mockito.Mockito.never())
                .upsertThemeStateKey(any(ThemeStateKey.class));
    }

    /**
     * C:U-166:正文块 when = HAS_TAG thought.&lt;key&gt;.done 引用未定义的念头时,试检查给提醒(不拦发布);
     * 已定义的念头不出提醒。负控:摘掉 collectChapters 里 chapterThoughtDoneConditions 的收集,本用例红。
     */
    @Test
    void chapterTextConditionOnUndefinedThoughtWarns() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"窗开着\","
                + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"thought.windw.done\"}},"
                + "{\"type\":\"text\",\"content\":\"风进来\","
                + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));

        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size(), issues.toString());
        assertEquals(TopicPublishCheckService.CODE_THOUGHT_CONDITION_UNDEFINED, issues.get(0).getCode());
        assertEquals("warn", issues.get(0).getLevel());
        assertTrue(issues.get(0).getMessage().contains("thought.windw.done"), issues.get(0).getMessage());
    }

    @Test
    void undeclaredThoughtDoneReportedByPrecheck() {
        assertThoughtPrecheckBlocked(thoughtRules("window"), "thought.other.done");
    }

    @Test
    void thoughtDeclarationsStayWithinTheCurrentTopic() {
        CmsTopic first = branchTopic();
        first.setJourneyRulesJson(thoughtRules("window"));
        first.setJourneyStoryJson(thoughtEnding("thought.window.done"));
        assertTrue(service.check(first).isEmpty());
        CmsTopic other = branchTopic();
        other.setId(TOPIC_ID + 1);
        other.setJourneyRulesJson(thoughtRules("another"));
        other.setJourneyStoryJson(thoughtEnding("thought.window.done"));

        List<PublishCheckIssue> issues = service.check(other);
        assertEquals(1, issues.size());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("thought.window.done"));
    }

    @Test
    void missingThoughtDeclarationsDoNotAuthorizeRuntimeTags() {
        assertThoughtPrecheckBlocked(null, "thought.window.done");
    }

    @Test
    void malformedThoughtRulesAreReportedAndNeverAuthorizeRuntimeTags() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson("{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"window\",\"need\":1}]}");
        topic.setJourneyStoryJson(thoughtEnding("thought.window.done"));

        List<PublishCheckIssue> issues = service.check(topic);
        assertTrue(issues.stream().anyMatch(issue -> TopicPublishCheckService.CODE_JOURNEY_RULES_INVALID.equals(issue.getCode())));
        assertTrue(issues.stream().anyMatch(issue -> TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED.equals(issue.getCode())
                && issue.getMessage().contains("thought.window.done")));
    }


    @Test
    void declaredThoughtCannotHideAdvancedNodeWritesFromPrecheck() {
        for (String effect : Arrays.asList(
                "{\"op\":\"ADD_TAG\",\"value\":\"thought.window.done\"}",
                "{\"op\":\"SET\",\"var\":\"thought.window.done\",\"value\":1}",
                "{\"op\":\"INC\",\"var\":\"thought.window.done\",\"value\":1}")) {
            CmsTopic topic = branchTopic(node("书房"));
            topic.setJourneyRulesJson(thoughtRules("window"));
            topic.setJourneyStoryJson(thoughtEnding("thought.window.done"));
            stubTemplate("{\"schemaVersion\":1,\"effects\":[" + effect + "]}");

            List<PublishCheckIssue> issues = service.check(topic);
            assertTrue(issues.stream().anyMatch(issue -> TopicPublishCheckService.CODE_BLOCK_CONFIG_INVALID.equals(issue.getCode())));
            assertTrue(issues.stream().anyMatch(issue -> TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED.equals(issue.getCode())
                    && issue.getMessage().contains("thought.window.done") && NODE_ID.equals(issue.getNodeId())));
        }
    }

    @Test
    void declaredThoughtCannotBeUsedAsOptionalClueOrRelationKey() {
        for (String extra : Arrays.asList(
                ",\"optionalClues\":[\"thought.window.done\"]",
                ",\"epilogues\":[{\"npcId\":7,\"relationKey\":\"thought.window.done\","
                        + "\"ally\":\"友\",\"neutral\":\"中\",\"guarded\":\"敌\"}]")) {
            CmsTopic topic = branchTopic();
            topic.setJourneyRulesJson(thoughtRules("window"));
            topic.setJourneyStoryJson(thoughtEnding("thought.window.done").replace(
                    "\"fallback\":true}]}", "\"fallback\":true}]" + extra + "}"));

            List<PublishCheckIssue> issues = service.check(topic);
            assertTrue(issues.stream().anyMatch(issue -> TopicPublishCheckService.CODE_JOURNEY_STORY_INVALID.equals(issue.getCode())),
                    "错类型不能因已声明念头而通过");
        }
    }


    @Test
    void declaredThoughtDoneChapterConditionChecksClean() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"content\":\"窗已打开\","
                + "\"when\":{\"op\":\"HAS_TAG\",\"value\":\"thought.window.done\"}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));

        assertTrue(service.check(topic).isEmpty());
    }

    @Test
    void undeclaredThoughtDoneChapterConditionRemainsAnError() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"when\":{\"op\":\"HAS_TAG\","
                + "\"value\":\"thought.other.done\"}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));

        List<PublishCheckIssue> issues = service.check(topic);
        assertEquals(1, issues.size());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("thought.other.done"));
        assertTrue(issues.get(0).getMessage().contains("章节故事流"));
    }


    @Test
    void legacyChapterNumericThoughtComparisonIsReportedAsInvalid() {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(thoughtRules("window"));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setBlocksJson("[{\"type\":\"text\",\"beat\":\"outcome\",\"when\":{\"op\":\"EQ\","
                + "\"var\":\"thought.window.done\",\"value\":1}}]");
        when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class))).thenReturn(Collections.singletonList(chapter));

        List<PublishCheckIssue> issues = service.check(topic);
        assertEquals(1, issues.size());
        assertEquals(TopicPublishCheckService.CODE_CHAPTER_BLOCKS_INVALID, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains("HAS_TAG"));
    }

    private void assertThoughtPrecheckBlocked(String rules, String tag) {
        CmsTopic topic = branchTopic();
        topic.setJourneyRulesJson(rules);
        topic.setJourneyStoryJson(thoughtEnding(tag));
        List<PublishCheckIssue> issues = service.check(topic);

        assertEquals(1, issues.size());
        assertEquals(TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED, issues.get(0).getCode());
        assertTrue(issues.get(0).getMessage().contains(tag), issues.get(0).getMessage());
    }

    private static String thoughtRules(String key) {
        return "{\"schemaVersion\":1,\"thoughts\":[{\"key\":\"" + key + "\",\"name\":\"那扇窗\",\"need\":1}]}";
    }

    private static String thoughtEnding(String tag) {
        return "{\"schemaVersion\":1,\"ending\":{\"endings\":["
                + "{\"code\":\"awake\",\"title\":\"醒来\",\"when\":[{\"op\":\"HAS_TAG\",\"value\":\"" + tag + "\"}]},"
                + "{\"code\":\"fallback\",\"title\":\"继续\",\"fallback\":true}]}}";
    }

    private CmsTopic branchTopic(CmsTopicNode... nodes) {
        return topic("BRANCH_GRAPH", nodes);
    }

    private CmsTopic linearTopic(CmsTopicNode... nodes) {
        return topic(null, nodes);
    }

    private CmsTopic topic(String routeMode, CmsTopicNode... nodes) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setRouteMode(routeMode);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class))).thenReturn(Arrays.asList(nodes));
        return topic;
    }

    private static CmsTopicNode node(String name) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setName(name);
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        return node;
    }

    private void stubTemplate(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson(advancedConfigJson);
        when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
    }

    private void stubRegistered(String... stateKeys) {
        List<ThemeStateKey> rows = new ArrayList<ThemeStateKey>();
        for (String stateKey : stateKeys) {
            ThemeStateKey key = new ThemeStateKey();
            key.setStateKey(stateKey);
            rows.add(key);
        }
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(rows);
    }

    private void stubRegisteredTyped(String stateKey, String keyType) {
        ThemeStateKey key = new ThemeStateKey();
        key.setStateKey(stateKey);
        key.setKeyType(keyType);
        when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(Collections.singletonList(key));
    }
}
