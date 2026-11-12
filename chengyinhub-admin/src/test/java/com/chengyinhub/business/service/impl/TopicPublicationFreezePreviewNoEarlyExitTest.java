package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.service.support.TopicRouteOutcomeReader;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard.PublicationProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 真实冻结算子的「不提前退出」与「后续校验仍会触发」证明 —— B4 交付
 * {@code previewPublication} 之后本项需求才真正收口,不能再停在显式「未检查」。
 *
 * <p>A/B 同一份内容、同一个编译器:
 * <ul>
 *   <li>非预演路径(2 参 {@code freezePublication},只读上下文):在模板线索自动登记点
 *       <b>必须</b>抛「自动线索声明必须加入主题写事务」—— 这就是当年预演跑不完的原因;</li>
 *   <li>预演路径({@code previewPublication}):该点不抛,冻结链跑到底;快照里出现
 *       {@code routeStations} 与 {@code stateKeySnapshotVersion},证明 {@code freezeKeys}
 *       等后续阶段确实执行过。</li>
 * </ul>
 *
 * <p>再用「后续阶段的非法配置」反向证明:线索登记点之后真的会继续校验 ——
 * 旅程规则里放一个不合法的属性键,预演必须报 {@code FREEZE_INVALID},而不是提前返回「未检查」。
 *
 * <p>负控:把 B4 的 {@code previewDeclarations} 分支去掉,{@code previewPassesTheClueRegistrationPoint}
 * 立刻退回成线索异常;把 {@code assertCompiledReferences}/{@code freezeKeys} 短路,
 * {@code previewDoesNotSwallowFailuresAfterTheCluePoint} 绿转红。
 */
class TopicPublicationFreezePreviewNoEarlyExitTest {

    private static final long TOPIC_ID = 10L;
    private static final long NODE_ID = 11L;
    private static final String TEMPLATE_CLUE = "clue.h_0123456789ab";

    /**
     * 合法运行配置:场景里节点 11 的玩法块声明一条线索。
     * 该块能通过 {@code AdvancedGameConfigValidator}(clue.h_ + 12 位十六进制),于是
     * 编译产出里带线索,冻结时必然走到 {@code registerTemplateClues}。
     */
    private static final String AUTHORED_WITH_CLUE =
            "{\"schemaVersion\":1,\"currentScene\":\"s1\",\"scenes\":[{\"id\":\"s1\",\"name\":\"主场景\","
                    + "\"nodeConfigs\":{\"" + NODE_ID + "\":{\"schemaVersion\":1,"
                    + "\"clues\":{\"" + TEMPLATE_CLUE + "\":\"纸条\"}}}}]}";

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ThemeStateKeyMapper keyMapper;
    private TopicRoutePublicationCompiler compiler;
    private ITopicRouteRuntimeService runtime;
    private TopicPublicationContentGuard guard;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(7L);
        topic.setName("寒冬");
        topic.setPublishMode("pro");
        topic.setRouteMode("LINEAR");
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(0);
        topic.setThemeRuntimeConfigJson(AUTHORED_WITH_CLUE);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setName("书房");
        node.setStatus(1L);

        lenient().when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
        lenient().when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        lenient().when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        lenient().when(templateMapper.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.emptyList());
        lenient().when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(Collections.emptyList());

        compiler = new TopicRoutePublicationCompiler(nodeMapper, templateMapper, chapterMapper, keyMapper,
                Clock.systemUTC(), new ObjectMapper(), new TopicRouteConfigValidator(), topicMapper);
        // 用 Mockito mock 而不是匿名实现:接口还有 tags/state/checkReceipt 等与本预演无关的抽象方法,
        // 匿名实现漏一个就编译失败,而这与「预演是否只读」毫无关系。只把两个端口接到真算子。
        runtime = mock(ITopicRouteRuntimeService.class);
        lenient().when(runtime.compileTemplateRules(any()))
                .thenAnswer(inv -> compiler.compileTemplateRules(inv.getArgument(0)));
        lenient().when(runtime.previewPublication(any(), any(), any()))
                .thenAnswer(inv -> compiler.previewPublication(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));

        guard = new TopicPublicationContentGuard();
        ReflectionTestUtils.setField(guard, "contentWriteGuard", mock(TopicContentWriteGuard.class));
        ReflectionTestUtils.setField(guard, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(guard, "cmsMemberTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(guard, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(guard, "omsTicketMapper", mock(OmsTicketMapper.class));
        ReflectionTestUtils.setField(guard, "themeStateKeyService", new ThemeStateKeyService(keyMapper));
        ReflectionTestUtils.setField(guard, "topicStateKeyRefService",
                new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper));
        ReflectionTestUtils.setField(guard, "routeOutcomeReader",
                new TopicRouteOutcomeReader(nodeMapper, templateMapper));
        ReflectionTestUtils.setField(guard, "topicRouteRuntimeService", runtime);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    /** A 面:非预演路径在线索登记点必须抛 —— 这是预演当年跑不完的确切原因。 */
    @Test
    void nonPreviewPathStillAbortsAtTheClueRegistrationPoint() {
        IllegalStateException aborted = assertThrows(IllegalStateException.class,
                () -> compiler.freezePublication(topic, null));
        assertTrue(aborted.getMessage().contains("自动线索声明必须加入主题写事务"),
                "非预演路径不该被放宽:" + aborted.getMessage());
    }

    /** B 面:预演路径跑到底,快照里带后续阶段的产物。 */
    @Test
    void previewPassesTheClueRegistrationPointAndCompletesTheChain() throws Exception {
        String frozen = compiler.previewPublication(topic, null, Collections.emptyList());

        assertNotNull(frozen);
        JsonNode snapshot = new ObjectMapper().readTree(frozen);
        assertEquals(1, snapshot.path("runtimeSnapshotVersion").asInt(), "冻结链已跑完");
        assertTrue(snapshot.path("routeStations").isArray(), "routeStations 在冻结末期写入,说明链未提前结束");
        assertEquals(1, snapshot.path("stateKeySnapshotVersion").asInt(),
                "freezeKeys 在线索登记点之后执行;它写入版本号即证明后续阶段真的跑了");
        assertTrue(stateKeys(snapshot).contains(TEMPLATE_CLUE),
                "派生线索应进入本局声明快照:" + stateKeys(snapshot));
    }

    /** B3 侧端到端:预检不再报「未检查」,也不报错误。 */
    @Test
    void guardPreviewReportsNeitherFailureNorIncompleteness() {
        List<PublicationProblem> problems = guard.collectPublicationProblems(topic);

        assertFalse(problems.stream().anyMatch(p -> PublicationProblem.CODE_FREEZE_INVALID.equals(p.getCode())),
                problems.toString());
        assertFalse(problems.stream().anyMatch(p -> PublicationProblem.CODE_FREEZE_PREVIEW_INCOMPLETE.equals(p.getCode())),
                "真实算子可用时不得再标「未检查」:" + problems);
    }

    /**
     * 线索点之后仍然会校验:配了通用运行规则却没配实际幕/任务,必须报错。
     *
     * <p>这条断言的是「预演不会在线索点之后就静默收工」—— 冻结链尾部的
     * {@code validateGameplayRuntime} 只要跑到就会抛这句。断言时排除线索异常本身,
     * 从而保证失败点不是那个已知的登记闸。
     */
    @Test
    void previewDoesNotSwallowFailuresAfterTheCluePoint() {
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"currentScene\":\"s1\",\"scenes\":[{\"id\":\"s1\","
                + "\"name\":\"主场景\",\"nodeConfigs\":{\"" + NODE_ID + "\":{\"schemaVersion\":1,"
                + "\"clues\":{\"" + TEMPLATE_CLUE + "\":\"纸条\"}}}}],"
                + "\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\","
                + "\"survival\":{\"zeroHpPolicy\":\"BLOCK_HP_COST\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"}}}");

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> compiler.previewPublication(topic, null, Collections.emptyList()));
        assertFalse(String.valueOf(refused.getMessage()).contains("自动线索声明必须加入主题写事务"),
                "失败点不能是线索登记闸:" + refused.getMessage());
        // 主干在同一段 validateGameplayRuntime 里先逐站核验玩法能否判完成(GameplaySupportMatrix.validateNode),
        // 这一站只有线索、没有可判定玩法,会先于「实际幕和任务」报出 —— 两者都证明预演跑到了线索点之后。
        String message = String.valueOf(refused.getMessage());
        assertTrue(message.contains("通用运行规则须配置实际幕和任务") || message.contains("这种玩法还不能确认玩家是否完成"),
                "冻结链尾部校验必须继续执行:" + refused.getMessage());
    }

    /** 同一份非法配置经 B3 预检必须是 error(而不是被归成「未检查」)。 */
    @Test
    void frozenTailFailureIsReportedAsErrorNotIncomplete() {
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"currentScene\":\"s1\",\"scenes\":[{\"id\":\"s1\","
                + "\"name\":\"主场景\",\"nodeConfigs\":{\"" + NODE_ID + "\":{\"schemaVersion\":1,"
                + "\"clues\":{\"" + TEMPLATE_CLUE + "\":\"纸条\"}}}}],"
                + "\"gameplayRuntime\":{\"runtimeScope\":\"PLAYER\","
                + "\"survival\":{\"zeroHpPolicy\":\"BLOCK_HP_COST\",\"actionLimitPolicy\":\"BLOCK_OVERFLOW\"}}}");

        List<PublicationProblem> problems = guard.collectPublicationProblems(topic);

        PublicationProblem error = problems.stream()
                .filter(p -> PublicationProblem.CODE_FREEZE_INVALID.equals(p.getCode()))
                .findFirst().orElseThrow(() -> new AssertionError("必须报 FREEZE_INVALID:" + problems));
        assertEquals(PublicationProblem.LEVEL_ERROR, error.getLevel());
        assertFalse(problems.stream().anyMatch(p -> PublicationProblem.CODE_FREEZE_PREVIEW_INCOMPLETE.equals(p.getCode())),
                "已经跑完并失败的不能同时标「未检查」:" + problems);
    }

    /** 预演不写库、不取行锁:真实算子全程只做普通读。 */
    @Test
    void realPreviewTakesNoRowLocksAndWritesNothing() {
        String before = topic.getThemeRuntimeConfigJson();

        guard.assertFreezePreview(topic);

        assertNoRowLocks(keyMapper, "ThemeStateKeyMapper");
        assertNoRowLocks(topicMapper, "CmsTopicMapper");
        assertNoRowLocks(nodeMapper, "CmsTopicNodeMapper");
        assertNoRowLocks(chapterMapper, "CmsTopicChapterMapper");
        assertNoRowLocks(templateMapper, "CmsMemberTemplateMapper");
        assertEquals(before, topic.getThemeRuntimeConfigJson(), "调用方实体不得被写");
    }

    /**
     * 上下文不泄漏:预演结束后,同一编译器上的下一次操作不继承预演声明
     * (B4 在 finally 里还原 currentContent;泄漏会让正式发布的声明凭空多出线索)。
     */
    @Test
    void previewDoesNotLeakItsContextIntoLaterOperations() {
        guard.assertFreezePreview(topic);

        // 同一编译器、同一主题,再走一次非预演冻结:仍应在同一线索点抛,说明没有残留 previewDeclarations。
        IllegalStateException aborted = assertThrows(IllegalStateException.class,
                () -> compiler.freezePublication(topic, null));
        assertTrue(aborted.getMessage().contains("自动线索声明必须加入主题写事务"),
                "预演上下文泄漏到了后续操作:" + aborted.getMessage());
    }

    /** 空投影也行:编译器自己把派生线索补进内存投影。 */
    @Test
    void emptyProjectionIsEnoughBecauseTheCompilerDerivesClues() {
        assertDoesNotThrow(() -> compiler.previewPublication(topic, null, Collections.emptyList()));
        assertDoesNotThrow(() -> compiler.previewPublication(topic, null, null));
    }

    private static List<String> stateKeys(JsonNode snapshot) {
        List<String> keys = new ArrayList<>();
        for (JsonNode row : snapshot.path("stateKeys")) keys.add(row.path("key").asText());
        return keys;
    }

    private static void assertNoRowLocks(Object mapper, String name) {
        for (org.mockito.invocation.Invocation invocation
                : org.mockito.Mockito.mockingDetails(mapper).getInvocations()) {
            String method = invocation.getMethod().getName();
            String lower = method.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("forupdate") || lower.contains("lock"),
                    name + " 在冻结预演里取了当前读/行锁:" + method);
            assertFalse(lower.startsWith("insert") || lower.startsWith("update") || lower.startsWith("delete")
                            || lower.startsWith("upsert") || lower.startsWith("save"),
                    name + " 在冻结预演里被写:" + method);
        }
    }
}
