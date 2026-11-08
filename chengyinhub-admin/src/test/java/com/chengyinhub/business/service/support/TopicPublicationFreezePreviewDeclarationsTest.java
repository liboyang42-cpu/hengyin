package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3 交给 B4 冻结预演的<b>声明投影</b>契约:必须是「发布自动登记之后」的集合,否则编译器仍会在
 * 模板线索登记点提前退出 —— 那样冻结预演就永远是「未检查」,本项需求落不了地。
 *
 * <p>投影口径与既有 preview 校验完全一致,不另立政策:
 * 已登记行(普通读)+ 选项标签(tag.*)+ 模板线索键(clue.h_*)。
 *
 * <p>钉死五件事:
 * <ol>
 *   <li>已登记行原样进入(类型/标签不覆盖);</li>
 *   <li>选项标签与模板线索被补进来,且每行都带 topicId(编译器 capture 会校验归属);</li>
 *   <li>按键去重,不产生重复声明(capture 遇重复直接抛);</li>
 *   <li>投影过程零写零锁 —— 不许为了预演偷偷登记;</li>
 *   <li>只读取一次注册表,且不调用需要写事务的 {@code listCurrentDeclarations}。</li>
 * </ol>
 *
 * <p>负控:把 {@code projectDeclarationsInMemory} 里的选项标签/线索合并删掉,第 2 条红;
 * 把去重改成直接 add,第 3 条红;把投影改回调 {@code deriveOptionTagsForPublication},第 4 条红。
 */
class TopicPublicationFreezePreviewDeclarationsTest {

    private static final long TOPIC_ID = 10L;
    private static final long NODE_ID = 11L;
    private static final long TEMPLATE_ID = 701L;
    /** 模板线索键必须符合 compiler 自己的口径:clue.h_ + 12 位十六进制。 */
    private static final String TEMPLATE_CLUE = "clue.h_aabbccddeeff";

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ThemeStateKeyMapper keyMapper;
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
        runtime = mock(ITopicRouteRuntimeService.class);

        topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(7L);
        topic.setName("寒冬");
        topic.setPublishMode("pro");
        topic.setRouteMode("LINEAR");
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setLifecycle(0);
        // 作者原文带一条模板线索:真实发布会把它自动登记成 clue.h_* 声明。
        topic.setThemeRuntimeConfigJson("{\"schemaVersion\":1,\"clues\":{\"" + TEMPLATE_CLUE + "\":\"纸条\"}}");

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        // 模板里引用一颗选项标签:tag.* 属于「发布时自动注册」的另一类。
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setValidationMethod(1);
        template.setAdvancedConfigJson("{\"schemaVersion\":1,"
                + "\"requires\":[{\"op\":\"HAS_TAG\",\"value\":\"tag.injured\"}]}");

        lenient().when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        lenient().when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        lenient().when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template));
        lenient().when(templateMapper.selectCmsMemberTemplateById(any())).thenReturn(template);
        lenient().when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        // 已登记行:作者自己确认过的一条 flag,投影必须原样保留。
        ThemeStateKey registered = new ThemeStateKey();
        registered.setTopicId(TOPIC_ID);
        registered.setStateKey("flag.visited");
        registered.setKeyType(ThemeStateKey.TYPE_FLAG);
        registered.setScope(ThemeStateKey.SCOPE_RUN);
        registered.setLabel("来过");
        lenient().when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(new ArrayList<>(List.of(registered)));

        lenient().when(runtime.compileTemplateRules(any(CmsTopic.class)))
                .thenReturn(topic.getThemeRuntimeConfigJson());
        lenient().when(runtime.previewPublication(any(CmsTopic.class), any(), anyList()))
                .thenReturn("{\"runtimeSnapshotVersion\":1}");

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

    /** 投影必须已经包含模板线索与选项标签,且每行带 topicId —— 这是预演能跑完的前提。 */
    @Test
    void previewReceivesDeclarationsThatAlreadyCoverAutoRegisteredKeys() {
        guard.assertFreezePreview(topic);

        List<ThemeStateKey> projected = captureProjected();
        Set<String> keys = projected.stream().map(ThemeStateKey::getStateKey).collect(Collectors.toSet());

        assertTrue(keys.contains("flag.visited"), "已登记行必须原样进入:" + keys);
        assertTrue(keys.contains(TEMPLATE_CLUE), "模板线索必须在投影里,否则编译器会提前抛:" + keys);
        assertTrue(keys.contains("tag.injured"), "选项标签必须在投影里:" + keys);
        for (ThemeStateKey row : projected) {
            assertEquals(TOPIC_ID, row.getTopicId(),
                    "每行必须带 topicId,编译器 capture 会校验归属:" + row.getStateKey());
            assertNotNull(row.getStateKey());
        }
    }

    /** 投影不覆盖作者已登记行的类型/标签。 */
    @Test
    void projectionDoesNotOverrideAuthorConfirmedRows() {
        guard.assertFreezePreview(topic);

        ThemeStateKey registered = captureProjected().stream()
                .filter(row -> "flag.visited".equals(row.getStateKey())).findFirst()
                .orElseThrow(() -> new AssertionError("已登记行丢失"));
        assertEquals(ThemeStateKey.TYPE_FLAG, registered.getKeyType());
        assertEquals("来过", registered.getLabel());
    }

    /** 去重:同一颗键只能出现一次(否则编译器 capture 直接抛「主题属性声明重复」)。 */
    @Test
    void projectionIsDeduplicatedByKey() {
        // 已登记行里放一条与模板线索同名的键:投影必须只保留一条。
        ThemeStateKey clash = new ThemeStateKey();
        clash.setTopicId(TOPIC_ID);
        clash.setStateKey(TEMPLATE_CLUE);
        clash.setKeyType(ThemeStateKey.TYPE_FLAG);
        clash.setScope(ThemeStateKey.SCOPE_RUN);
        clash.setLabel("作者写过的线索");
        lenient().when(keyMapper.selectByTopicId(TOPIC_ID)).thenReturn(new ArrayList<>(List.of(clash)));

        guard.assertFreezePreview(topic);

        List<String> keys = captureProjected().stream().map(ThemeStateKey::getStateKey).collect(Collectors.toList());
        assertEquals(keys.size(), Set.copyOf(keys).size(), "投影出现重复声明:" + keys);
        assertEquals("作者写过的线索", captureProjected().stream()
                        .filter(row -> TEMPLATE_CLUE.equals(row.getStateKey())).findFirst()
                        .orElseThrow().getLabel(),
                "已登记行优先,不被投影覆盖");
    }

    /** 零写零锁:投影不许为了预演偷偷登记任何键。 */
    @Test
    void projectionNeverWritesOrLocksTheRegistry() {
        guard.assertFreezePreview(topic);

        verify(keyMapper, never()).upsertThemeStateKey(any());
        verify(keyMapper, never()).lockTopicForStateKeyWrite(any());
        verify(keyMapper, never()).selectByTopicIdAndStateKeyForUpdate(any(), any());
        verify(keyMapper, never()).selectByTopicIdForUpdate(any());
        verify(keyMapper, never()).deleteByTopicIdAndStateKey(any(), any());
    }

    /** 只走普通读:预检不得要求写事务(那会把只读入口变成写链路的一环)。 */
    @Test
    void projectionUsesPlainReadOnly() {
        guard.assertFreezePreview(topic);

        verify(keyMapper).selectByTopicId(eq(TOPIC_ID));
        verify(keyMapper, never()).selectByTopicIdForUpdate(any());
    }

    @Test
    void linearTopicPassesNoRouteSnapshot() {
        guard.assertFreezePreview(topic);

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(runtime).previewPublication(any(CmsTopic.class), snapshot.capture(), anyList());
        assertEquals(null, snapshot.getValue(), "线性主题不合成路线快照,冻结内部用 linearSnapshot");
    }

    /** 冻结返回空快照 = 预演失败,不能静默通过。 */
    @Test
    void blankFrozenSnapshotFailsThePreview() {
        when(runtime.previewPublication(any(CmsTopic.class), any(), anyList())).thenReturn("  ");

        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> guard.assertFreezePreview(topic));
        assertEquals("主题发布内容快照为空", refused.getMessage());
    }

    /** 预演端口缺失(B4 未交付 / 实现未覆写)必须是显式「未检查」,不是 error,也不是通过。 */
    @Test
    void unavailablePreviewPortIsReportedAsUncheckedNotFailed() {
        when(runtime.previewPublication(any(CmsTopic.class), any(), anyList()))
                .thenThrow(new UnsupportedOperationException("not implemented"));

        List<TopicPublicationContentGuard.PublicationProblem> problems = guard.collectPublicationProblems(topic);

        assertFalse(problems.stream().anyMatch(p -> TopicPublicationContentGuard.PublicationProblem
                        .CODE_FREEZE_INVALID.equals(p.getCode())),
                "端口缺失不是发布失败:" + problems);
        TopicPublicationContentGuard.PublicationProblem incomplete = problems.stream()
                .filter(p -> TopicPublicationContentGuard.PublicationProblem
                        .CODE_FREEZE_PREVIEW_INCOMPLETE.equals(p.getCode()))
                .findFirst().orElseThrow(() -> new AssertionError("必须显式报告未检查:" + problems));
        assertEquals(TopicPublicationContentGuard.PublicationProblem.LEVEL_WARN, incomplete.getLevel());
        assertTrue(incomplete.getMessage().contains("未检查"), incomplete.getMessage());
    }

    /** 预演抛出的真实配置错误必须报 error,不能被归成「未检查」。 */
    @Test
    void realFreezeFailureIsReportedAsError() {
        when(runtime.previewPublication(any(CmsTopic.class), any(), anyList()))
                .thenThrow(new IllegalArgumentException("节点 11 方案 x 结局章不属于本主题"));

        List<TopicPublicationContentGuard.PublicationProblem> problems = guard.collectPublicationProblems(topic);

        TopicPublicationContentGuard.PublicationProblem error = problems.stream()
                .filter(p -> TopicPublicationContentGuard.PublicationProblem
                        .CODE_FREEZE_INVALID.equals(p.getCode()))
                .findFirst().orElseThrow(() -> new AssertionError("真实冻结错误必须报 error:" + problems));
        assertEquals(TopicPublicationContentGuard.PublicationProblem.LEVEL_ERROR, error.getLevel());
        assertTrue(error.getMessage().contains("结局章不属于本主题"), error.getMessage());
    }

    private List<ThemeStateKey> captureProjected() {
        ArgumentCaptor<List<ThemeStateKey>> captured = ArgumentCaptor.forClass(List.class);
        verify(runtime).previewPublication(any(CmsTopic.class), any(), captured.capture());
        List<ThemeStateKey> projected = captured.getValue();
        assertNotNull(projected);
        return projected;
    }
}
