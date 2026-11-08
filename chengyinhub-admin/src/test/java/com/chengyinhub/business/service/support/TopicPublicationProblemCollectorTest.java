package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard.PublicationProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * 发布前试检查的收集器:不新造判据,逐条调用发布闸自己的 assert,把异常收敛成可定位问题。
 *
 * <p>钉四件事:
 * <ol>
 *   <li>同一条规则的文案与发布闸完全一致(预检不会说另一套话);</li>
 *   <li>多类问题一次列全,不因第一条失败就停;</li>
 *   <li>节点级问题带章节 / 节点 / 字段定位;</li>
 *   <li>只读:不写库、不加锁、不修改调用方传进来的主题对象。</li>
 * </ol>
 */
class TopicPublicationProblemCollectorTest {

    private static final long TOPIC_ID = 21L;
    private static final long NODE_ID = 301L;
    private static final long TEMPLATE_ID = 401L;

    private TopicPublicationContentGuard guard;
    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private OmsTicketMapper ticketMapper;
    private ThemeStateKeyMapper keyMapper;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        lenient().when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.<CmsTopicNode>emptyList());
        lenient().when(ticketMapper.selectOmsTicketList(any())).thenReturn(Collections.emptyList());

        guard = new TopicPublicationContentGuard();
        ReflectionTestUtils.setField(guard, "contentWriteGuard", mock(TopicContentWriteGuard.class));
        ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(guard, "cmsMemberTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(guard, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(guard, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(guard, "themeStateKeyService", new ThemeStateKeyService(keyMapper));
        ReflectionTestUtils.setField(guard, "topicStateKeyRefService",
                new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper));
        ReflectionTestUtils.setField(guard, "routeOutcomeReader",
                new com.chengyinhub.business.service.support.TopicRouteOutcomeReader(nodeMapper, templateMapper));
        // 冻结预演:用真冻结入口,但只让它跑只读路径。返回固定快照,证明调用链无需写库。
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        lenient().when(runtime.previewPublication(any(CmsTopic.class), any(), any()))
                .thenReturn("{\"runtimeSnapshotVersion\":1}");
        ReflectionTestUtils.setField(guard, "topicRouteRuntimeService", runtime);
    }

    /**
     * 同一条内容完整度规则:发布闸抛出的那句话,就是预检列出的那条 message。
     * 负控:预检改成自带文案,本用例红。
     */
    @Test
    void previewRepeatsTheGateWordingForTheSameFact() {
        CmsTopic topic = topic();
        topic.setName("");

        PublicationProblem readiness = only(guard.collectPublicationProblems(topic),
                PublicationProblem.CODE_TOPIC_NAME_REQUIRED);
        assertEquals("name", readiness.getField());
        assertEquals("主题名称不能为空", readiness.getMessage());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> guard.assertPublishReady(topic));
        assertTrue(thrown.getMessage().contains(readiness.getMessage()), thrown.getMessage());
        assertTrue(thrown.getMessage().startsWith("发布前请先补全:"), thrown.getMessage());
    }

    /** 预检一次列全:名称缺失与坏数值规则同时出现时两条都在。 */
    @Test
    void previewCollectsEveryFailureWithoutStopping() {
        CmsTopic topic = topic();
        topic.setName("");
        topic.setJourneyRulesJson("{broken");

        List<PublicationProblem> preview = guard.collectPublicationProblems(topic);

        assertTrue(preview.stream().anyMatch(p -> PublicationProblem.CODE_TOPIC_NAME_REQUIRED.equals(p.getCode())),
                preview.toString());
        assertTrue(preview.stream().anyMatch(p -> PublicationProblem.CODE_STATE_KEY_UNREGISTERED.equals(p.getCode())),
                preview.toString());
    }

    /**
     * 块内 NODE_COMPLETED 的坏引用:发布闸把定位尾巴拼进消息,预检把它拆成结构化定位,
     * 作者端才能跳到「哪一站、哪个玩法块、哪个字段」。
     */
    @Test
    void nodeScopedFailureCarriesNodeAndFieldLocator() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(101L);
        node.setName("书房");
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        lenient().when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");
        lenient().when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
        lenient().when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template));

        PublicationProblem sysVar = only(guard.collectPublicationProblems(topic()),
                PublicationProblem.CODE_BLOCK_SYS_VAR_INVALID);

        assertEquals(NODE_ID, sysVar.getNodeId());
        assertEquals("nodes[nodeId=" + NODE_ID + "].template[templateId=" + TEMPLATE_ID + "].advancedConfigJson",
                sysVar.getField());
        assertTrue(sysVar.getMessage().contains("NODE_COMPLETED"), sysVar.getMessage());
        assertFalse(sysVar.getMessage().contains("[topicId="),
                "支持用的定位尾巴不应原样给作者:" + sysVar.getMessage());
    }

    /** 预检只读:任何 mapper 上都不许出现 insert/update/delete 调用。 */
    @Test
    void previewNeverWritesThroughAnyMapper() {
        CmsTopic topic = topic();
        topic.setName("");

        guard.collectPublicationProblems(topic);

        assertNoWrites(nodeMapper, "CmsTopicNodeMapper");
        assertNoWrites(templateMapper, "CmsMemberTemplateMapper");
        assertNoWrites(chapterMapper, "CmsTopicChapterMapper");
        assertNoWrites(ticketMapper, "OmsTicketMapper");
        assertNoWrites(keyMapper, "ThemeStateKeyMapper");
    }

    /**
     * 预检不得修改调用方实体:路线快照编译会写回 themeRuntimeConfigJson,预检只跑只读结构闸,
     * 调用前后主题的每个作者字段必须原样。
     */
    @Test
    void previewLeavesTheCallerTopicUntouched() {
        CmsTopic topic = topic();
        topic.setRouteMode("BRANCH_GRAPH");
        topic.setRouteGraphJson("{\"nodes\":[]}");
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1}");
        String before = topic.toString();

        guard.collectPublicationProblems(topic);

        assertEquals(before, topic.toString());
        assertEquals("{\"schemaVersion\":1}", topic.getThemeRuntimeConfigJson());
    }

    /**
     * 地点合同(B2):必填槽未绑定 = 发布会被拦;旧模板沿用的 LEGACY_UNVERIFIED 只是提醒,
     * 不得把旧请求产生的正常副本挡在门外。
     *
     * <p>判据全部来自 {@code TopicLocationContract.bindingIssues},本域不复制政策。
     */
    @Test
    void locationContractOnlyBlocksErrorsAndWarnsOnLegacy() {
        CmsTopic unbound = topic();
        unbound.setContentContractJson("{\"schemaVersion\":1,"
                + "\"locationSlots\":[{\"slotKey\":\"loc-1\",\"purpose\":\"SPACE\",\"required\":true}],"
                + "\"locationBindings\":[]}");
        PublicationProblem unboundIssue = only(guard.collectPublicationProblems(unbound),
                PublicationProblem.CODE_LOCATION_BINDING_INVALID);
        assertEquals(PublicationProblem.LEVEL_ERROR, unboundIssue.getLevel());
        assertTrue(unboundIssue.getMessage().contains("尚未绑定"), unboundIssue.getMessage());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> guard.assertLocationBindingsPublishable(unbound));
        assertTrue(refused.getMessage().contains("主题发布失败"), refused.getMessage());

        CmsTopic legacy = topic();
        legacy.setContentContractJson("{\"schemaVersion\":1,"
                + "\"locationSlots\":[{\"slotKey\":\"loc-1\",\"purpose\":\"SPACE\",\"required\":true}],"
                + "\"locationBindings\":[{\"slotKey\":\"loc-1\",\"nodeId\":\"301\","
                + "\"verificationStatus\":\"LEGACY_UNVERIFIED\"}]}");
        PublicationProblem legacyIssue = only(guard.collectPublicationProblems(legacy),
                PublicationProblem.CODE_LOCATION_BINDING_INVALID);
        assertEquals(PublicationProblem.LEVEL_WARN, legacyIssue.getLevel());
        guard.assertLocationBindingsPublishable(legacy);   // 提醒不阻断
    }

    /** 没有任何地点合同的旧主题不增加门槛。 */
    @Test
    void topicWithoutLocationContractHasNoLocationProblem() {
        assertTrue(guard.collectPublicationProblems(topic()).stream()
                .noneMatch(p -> PublicationProblem.CODE_LOCATION_BINDING_INVALID.equals(p.getCode())));
    }

    /**
     * 冻结预演必须覆盖「只有真跑冻结才会暴露」的错误(结局章不属于本主题、玩法运行期计划不合法等),
     * 这是结构校验闸看不到的一层。
     *
     * <p>负控:把 collectPublicationProblems 里的冻结预演调用删掉,本用例红。
     */
    @Test
    void freezePreviewSurfacesErrorsThatOnlyFreezingCatches() {
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                (com.chengyinhub.business.service.ITopicRouteRuntimeService)
                        ReflectionTestUtils.getField(guard, "topicRouteRuntimeService");
        org.mockito.Mockito.doThrow(new IllegalArgumentException("节点 301 方案 bad 结局章不属于本主题"))
                .when(runtime).previewPublication(any(CmsTopic.class), any(), any());

        PublicationProblem problem = only(guard.collectPublicationProblems(topic()),
                PublicationProblem.CODE_FREEZE_INVALID);

        assertEquals(PublicationProblem.LEVEL_ERROR, problem.getLevel());
        assertTrue(problem.getMessage().contains("结局章不属于本主题"), problem.getMessage());
    }

    /**
     * 「模板线索待自动登记」会**提前结束**冻结,其后的校验根本没跑。所以这里既不能报 error(不是真拦截),
     * 更不能当作通过 —— 必须显式给出 warn「未检查」。
     *
     * <p>负控:把 collectFreezePreview 的这条分支改回「静默跳过」,本用例红(会误报为已检查)。
     */
    @Test
    void freezePreviewMarksItselfIncompleteInsteadOfPassing() {
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                (com.chengyinhub.business.service.ITopicRouteRuntimeService)
                        ReflectionTestUtils.getField(guard, "topicRouteRuntimeService");
        org.mockito.Mockito.doThrow(new IllegalStateException("自动线索声明必须加入主题写事务"))
                .when(runtime).previewPublication(any(CmsTopic.class), any(), any());

        List<PublicationProblem> problems = guard.collectPublicationProblems(topic());

        assertTrue(problems.stream().noneMatch(p -> PublicationProblem.CODE_FREEZE_INVALID.equals(p.getCode())),
                "自动登记的模板线索不是发布缺口:" + problems);
        PublicationProblem incomplete = only(problems, PublicationProblem.CODE_FREEZE_PREVIEW_INCOMPLETE);
        assertEquals(PublicationProblem.LEVEL_WARN, incomplete.getLevel());
        assertTrue(incomplete.getMessage().contains("未检查"), incomplete.getMessage());
    }
    /**
     * 冻结预演只喂一次性副本:编译会把 themeRuntimeConfigJson 写回收到的那个实体,
     * 调用方的主题对象必须一字不变。
     *
     * <p>负控:把 assertFreezePreview 改成把调用方实体直接交给预演,本用例红。
     */
    @Test
    void freezePreviewNeverMutatesTheCallerTopic() {
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                (com.chengyinhub.business.service.ITopicRouteRuntimeService)
                        ReflectionTestUtils.getField(guard, "topicRouteRuntimeService");
        org.mockito.Mockito.doAnswer(call -> {
            CmsTopic received = call.getArgument(0);
            received.setThemeRuntimeConfigJson("{\"compiled\":true}");   // 模拟 compileTemplateRules 的写回
            return "{\"runtimeSnapshotVersion\":1}";
        }).when(runtime).previewPublication(any(CmsTopic.class), any(), any());
        CmsTopic topic = topic();
        topic.setThemeRuntimeConfigJson("{\"authored\":true}");

        guard.assertFreezePreview(topic);

        assertEquals("{\"authored\":true}", topic.getThemeRuntimeConfigJson(),
                "预检不得把编译结果写回调用方实体");
    }

    /** 写事务里拒绝预演,而不是偷偷走当前读锁。 */
    @Test
    void freezePreviewRefusesInsideAWriteTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager
                .setCurrentTransactionReadOnly(false);
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> guard.assertFreezePreview(topic()));
            assertEquals("发布预检必须在只读或非事务上下文执行", refused.getMessage());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .setCurrentTransactionReadOnly(false);
        }
    }
    private static PublicationProblem only(List<PublicationProblem> problems, String code) {
        List<PublicationProblem> matched = problems.stream()
                .filter(problem -> code.equals(problem.getCode())).collect(Collectors.toList());
        assertEquals(1, matched.size(), "code " + code + " → " + problems);
        PublicationProblem problem = matched.get(0);
        assertNotNull(problem.getMessage());
        return problem;
    }

    private static void assertNoWrites(Object mapper, String name) {
        for (Invocation invocation : mockingDetails(mapper).getInvocations()) {
            String method = invocation.getMethod().getName();
            assertFalse(method.startsWith("insert") || method.startsWith("update") || method.startsWith("delete"),
                    name + " 在预检里被写:" + method);
            assertFalse(method.contains("ForUpdate") || method.contains("lock"),
                    name + " 在预检里取了当前读/行锁:" + method);
        }
    }

    private static CmsTopic topic() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(7L);
        topic.setName("寒冬");
        topic.setPublishMode("pro");
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(0);
        return topic;
    }
}
