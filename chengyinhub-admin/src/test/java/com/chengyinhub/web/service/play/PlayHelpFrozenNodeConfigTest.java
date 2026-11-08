package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayHelpUseVO;
import com.chengyinhub.business.domain.vo.PlayHelpViewVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IAdvancedGameRuntimeService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.PlayHelpCostApplication;
import com.chengyinhub.business.service.support.PlayHelpReceiptReadModel;
import com.chengyinhub.business.service.support.PlayHelpRunQuotaApplication;
import com.chengyinhub.business.service.support.PlayHelpUsagePolicy;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.util.AdvancedGamePublicProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C04-F1：新增玩法段在本局帮助面上的**真实冻结 → 消费**闭环回归。
 *
 * <p>链路不是手抄夹具：先用**真实发布编译器** {@code TopicRoutePublicationCompiler.freezePublication}
 * 从模板 {@code advancedConfigJson} 生成本局冻结快照，再把这份真实快照塞给
 * {@link PlayHelpApplication}，断言 view/use 的行为。
 *
 * <p>为什么必须这样测：R18 冻结把块配置写在 {@code nodeConfigs[nodeId]}，{@code nodeAnswers[nodeId]}
 * 只带判定/呈现字段。此前 {@code resolve} 只从 {@code nodeAnswers} 找 {@code hintLadder}，
 * 于是新局 help 恒为 {@code enabled=false}，同一快照里的整局 N 却读得到 —— 「有额度、无层级」。
 *
 * <p>同时钉住两条边界：冻结配置显式没有阶梯时**不得**回退到实时作者配置（作者发布后改模板不改变本局）；
 * 公共投影与帮助层级面不携带提示正文/答案。
 *
 * <p>编译器是包内类（{@code final class}），本包调用它只能用反射取真实实现；断言仍打在真实输出上。
 */
class PlayHelpFrozenNodeConfigTest {

    private static final long MEMBER = 77L;
    private static final long RUN = 5001L;
    private static final long TOPIC = 10L;
    private static final long NODE = 11L;
    private static final long CHAPTER = 20L;
    private static final long TEMPLATE = 701L;
    private static final String INSTANCE = "inst-f1";
    private static final String HINT_TEXT = "观察屋檐下的年份";

    private static final ObjectMapper JSON = new ObjectMapper();

    private PlayHelpApplication application;
    private TopicRouteSessionMapper sessionMapper;
    private CmsTopicNodeMapper nodeMapper;
    private PlayAttemptLogMapper attemptMapper;
    private PlayNodeContext playNodeContext;
    private PlayHelpRunQuotaApplication quotaApplication;
    private PlayHelpReceiptReadModel receiptReadModel;
    private ICmsMemberTemplateService templateService;
    private IPlayerNodeProgressService progressService;
    private GameRuntimeSessionMapper gameSessionMapper;
    private IAdvancedGameRuntimeService runtimeService;
    private PlayNodeCompletionApplication completionApplication;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(TopicRouteSessionMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        attemptMapper = mock(PlayAttemptLogMapper.class);
        playNodeContext = mock(PlayNodeContext.class);
        quotaApplication = mock(PlayHelpRunQuotaApplication.class);
        receiptReadModel = mock(PlayHelpReceiptReadModel.class);
        templateService = mock(ICmsMemberTemplateService.class);
        progressService = mock(IPlayerNodeProgressService.class);
        gameSessionMapper = mock(GameRuntimeSessionMapper.class);
        runtimeService = mock(IAdvancedGameRuntimeService.class);
        completionApplication = mock(PlayNodeCompletionApplication.class);

        application = new PlayHelpApplication();
        ReflectionTestUtils.setField(application, "topicRouteSessionMapper", sessionMapper);
        ReflectionTestUtils.setField(application, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(application, "playAttemptLogMapper", attemptMapper);
        ReflectionTestUtils.setField(application, "playNodeContext", playNodeContext);
        ReflectionTestUtils.setField(application, "playHelpRunQuotaApplication", quotaApplication);
        ReflectionTestUtils.setField(application, "playHelpReceiptReadModel", receiptReadModel);
        ReflectionTestUtils.setField(application, "cmsMemberTemplateService", templateService);
        ReflectionTestUtils.setField(application, "playerNodeProgressService", progressService);
        ReflectionTestUtils.setField(application, "gameRuntimeSessionMapper", gameSessionMapper);
        ReflectionTestUtils.setField(application, "advancedGameRuntimeService", runtimeService);
        ReflectionTestUtils.setField(application, "playNodeCompletionApplication", completionApplication);
        ReflectionTestUtils.setField(application, "objectMapper", JSON);
    }

    // ---------------------------------------------------------------- 新冻结形状

    /**
     * 新局：模板配了 hintLadder + hintFreeUsesPerRun；冻结后节点答案里没有阶梯，
     * 阶梯与 N 只在 {@code nodeConfigs[nodeId]}。view 必须给出层级、use 必须给出获准内容。
     */
    @Test
    void frozenNodeConfigsLadderDrivesHelpViewAndUse() throws Exception {
        Fixture fixture = new Fixture(advancedConfig(3, 2));
        String frozen = fixture.freeze();

        // 冻结形状本身就是这次修复的前提：阶梯不在节点答案里。
        JsonNode nodeConfig = JSON.readTree(frozen).path("nodeConfigs").path(String.valueOf(NODE));
        assertTrue(nodeConfig.path("hintLadder").isArray(), "hintLadder 必须冻结在 nodeConfigs[nodeId]");
        assertEquals(2, nodeConfig.path("hintFreeUsesPerRun").asInt());
        JsonNode frozenAnswer = JSON.readTree(frozen).path("nodeAnswers").path(String.valueOf(NODE));
        assertFalse(frozenAnswer.has("hintLadder"), "节点答案不应带 hintLadder");
        assertFalse(frozenAnswer.has("advancedConfigJson"), "节点答案不应带整份进阶配置串");

        givenRun(frozen);
        when(quotaApplication.remainingFreeUses(anyLong(), any(), any())).thenReturn(2);

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled(), "冻结在 nodeConfigs 的阶梯必须被 help 读到");
        assertEquals(3, view.layers().size());
        assertEquals(1, view.layers().get(0).level());
        assertEquals(PlayHelpUsagePolicy.TYPE_TEXT, view.layers().get(0).type());
        assertTrue(view.layers().get(0).free(), "还有整局免费额度时首次解锁免费");
        assertEquals(0, view.layers().get(0).costPoints());
        assertTrue(view.layers().get(2).free(), "剩余额度覆盖到其它层");
        assertEquals(0, view.layers().get(2).costPoints());

        // 额度用尽后同一份冻结层级改为显示作者配置的积分代价。
        when(quotaApplication.remainingFreeUses(anyLong(), any(), any())).thenReturn(0);
        PlayHelpViewVO exhausted = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));
        assertFalse(exhausted.layers().get(0).free());
        assertEquals(30, exhausted.layers().get(2).costPoints());

        // 层级面只有代价/状态，提示正文只能从 /use 拿。
        assertFalse(view.toString().contains(HINT_TEXT), "view 不得携带提示正文");
        assertFalse(exhausted.toString().contains(HINT_TEXT), "view 不得携带提示正文");

        // 公共模板投影同样是白名单：私有阶梯与整局 N 一个字都不出去。
        String projected = AdvancedGamePublicProjection.safeConfigJson(nodeConfig.toString());
        assertTrue(projected != null, "公共投影不应失败");
        assertFalse(projected.contains(HINT_TEXT), "公共投影不得携带提示正文");
        assertFalse(projected.contains("hintFreeUsesPerRun"), "公共投影不得下发整局免费次数");
        assertFalse(projected.contains("hintLadder"), "公共投影不得下发提示阶梯");
    }

    /** 免费额度用尽后按作者配置代价扣分；requestId 与路线局作用域原样交给幂等/台账入口。 */
    @Test
    void exhaustedQuotaChargesConfiguredPointsAndKeepsRouteScopeAndRequestId() throws Exception {
        Fixture fixture = new Fixture(advancedConfig(3, 1));
        givenRun(fixture.freeze());
        when(quotaApplication.use(anyLong(), any(), anyInt(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new PlayHelpRunQuotaApplication.QuotaOutcome(
                        new PlayHelpCostApplication.Charge(true, false, 1, 30, 70L, "bk-f1"), true, false, 0));

        PlayHelpUseVO receipt = application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE),
                1, "HELP", "req-f1");

        assertEquals(1, receipt.level());
        assertEquals(PlayHelpUsagePolicy.TYPE_TEXT, receipt.type());
        assertEquals(HINT_TEXT, receipt.content(), "内容只来自获准层的 payload");
        assertEquals(30, receipt.chargedPoints(), "作者配置的积分代价原样回传");
        assertEquals(70L, receipt.balanceAfter());
        assertTrue(receipt.applied());

        ArgumentCaptor<PlayHelpUsagePolicy.Scope> scope = ArgumentCaptor.forClass(PlayHelpUsagePolicy.Scope.class);
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(quotaApplication).use(eq(MEMBER), scope.capture(), eq(1), eq(1), eq(0), eq(30), requestId.capture());
        assertEquals(PlayHelpUsagePolicy.RunKind.TOPIC_ROUTE, scope.getValue().kind(), "路线局不得串成高级玩法局");
        assertEquals(INSTANCE, scope.getValue().playInstance());
        assertEquals(TOPIC, scope.getValue().topicId().longValue());
        assertEquals(NODE, scope.getValue().nodeId().longValue());
        assertEquals("req-f1", requestId.getValue(), "同一动作号必须原样落到幂等入口");
    }

    // ---------------------------------------------------------------- 无阶梯不得偷用实时配置

    /**
     * 冻结时作者没配阶梯；发布后作者在模板里加了阶梯（实时配置已变）。
     * 本局仍必须按冻结内容走旧行为：没有层级、拒绝使用 —— 不得回退到实时作者配置。
     */
    @Test
    void frozenConfigWithoutLadderNeverAdoptsLiveAuthorConfig() throws Exception {
        Fixture fixture = new Fixture("{}");
        String frozen = fixture.freeze();
        assertFalse(JSON.readTree(frozen).path("nodeConfigs").path(String.valueOf(NODE)).has("hintLadder"),
                "冻结块配置显式没有阶梯");

        // 发布之后作者改了实时模板：这份阶梯不属于已开局的本局。
        fixture.template.setAdvancedConfigJson(advancedConfig(3, 2));
        givenRun(frozen);

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertFalse(view.enabled(), "冻结没有阶梯就不能从实时模板借一份");
        assertTrue(view.layers().isEmpty());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> application.use(MEMBER, String.valueOf(RUN), String.valueOf(NODE), 1, "HELP", "req-f1"));
        assertEquals("该级帮助未配置", refused.getMessage());
        verify(quotaApplication, org.mockito.Mockito.never())
                .use(anyLong(), any(), anyInt(), any(), anyInt(), anyInt(), any());
        verifyNoInteractions(templateService);
    }

    // ---------------------------------------------------------------- 旧冻结兼容

    /** 旧冻结把阶梯直接写在节点答案里：仍按原样生效，不因新增 nodeConfigs 读取而失效。 */
    @Test
    void legacyNodeAnswerLadderStillWorks() {
        givenRun("{\"nodeAnswers\":{\"" + NODE + "\":{\"hintLadder\":["
                + "{\"level\":1,\"type\":\"TEXT\",\"payload\":\"" + HINT_TEXT + "\"}],"
                + "\"hint1\":\"看门牌\",\"questionAnswer\":\"Bridge 42\"}}}");

        PlayHelpViewVO view = application.view(MEMBER, String.valueOf(RUN), String.valueOf(NODE));

        assertTrue(view.enabled());
        assertEquals(1, view.layers().size());
        assertEquals(PlayHelpUsagePolicy.TYPE_TEXT, view.layers().get(0).type());
    }

    // ---------------------------------------------------------------- fixtures

    private static String advancedConfig(int lastLevel, int freeUsesPerRun) {
        StringBuilder ladder = new StringBuilder();
        for (int level = 1; level <= lastLevel; level++) {
            if (level > 1) ladder.append(',');
            ladder.append("{\"level\":").append(level).append(",\"type\":\"TEXT\",\"payload\":\"")
                    .append(level == 1 ? HINT_TEXT : "第" + level + "层提示")
                    .append("\",\"cost\":{\"points\":30},\"freeQuota\":0");
            ladder.append('}');
        }
        return "{\"schemaVersion\":1,\"hintFreeUsesPerRun\":" + freeUsesPerRun
                + ",\"hintLadder\":[" + ladder + "]}";
    }

    private void givenRun(String configSnapshotJson) {
        TopicRouteSession session = new TopicRouteSession();
        session.setId(RUN);
        session.setMemberId(MEMBER);
        session.setTopicId(TOPIC);
        session.setPlayInstanceId(INSTANCE);
        session.setStatus(TopicRouteSession.STATUS_RUNNING);
        session.setConfigSnapshotJson(configSnapshotJson);
        when(sessionMapper.selectAccessibleById(RUN, MEMBER)).thenReturn(session);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setTopicId(TOPIC);
        when(nodeMapper.selectCmsTopicNodeById(NODE)).thenReturn(node);
        when(playNodeContext.resolveCtx(MEMBER, null, String.valueOf(TOPIC), node)).thenReturn(ctx());
        when(attemptMapper.selectByIdempotencyKey(any())).thenReturn((PlayAttemptLog) null);
    }

    private static PlayNodeContext.PlayCtx ctx() {
        PlayNodeContext.PlayCtx ctx = new PlayNodeContext.PlayCtx();
        ctx.actId = 0L;
        ctx.topicId = TOPIC;
        return ctx;
    }

    /** 真实发布编译器 + 最小行夹具（与 TopicPublicationContentSnapshotTest 同一套形状）。 */
    private static final class Fixture {
        final CmsTopic topic = new CmsTopic();
        final CmsTopicNode node = new CmsTopicNode();
        final CmsTopicChapter chapter = new CmsTopicChapter();
        final CmsMemberTemplate template = new CmsMemberTemplate();
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);

        Fixture(String advancedConfigJson) {
            topic.setId(TOPIC);
            topic.setRouteMode("LINEAR");
            node.setId(NODE);
            node.setTopicId(TOPIC);
            node.setChapterId(CHAPTER);
            node.setTemplateId(TEMPLATE);
            node.setStatus(1L);
            chapter.setId(CHAPTER);
            chapter.setTopicId(TOPIC);
            template.setId(TEMPLATE);
            template.setValidationMethod(1);
            template.setQuestionAnswer("Bridge 42");
            template.setAdvancedConfigJson(advancedConfigJson);
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
            when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.emptyList());
        }

        String freeze() throws Exception {
            Class<?> type = Class.forName("com.chengyinhub.business.service.impl.TopicRoutePublicationCompiler");
            Constructor<?> constructor = type.getDeclaredConstructor(CmsTopicNodeMapper.class,
                    CmsMemberTemplateMapper.class, CmsTopicChapterMapper.class, ThemeStateKeyMapper.class,
                    Clock.class, ObjectMapper.class, TopicRouteConfigValidator.class, CmsTopicMapper.class);
            constructor.setAccessible(true);
            Object compiler = constructor.newInstance(nodes, templates, chapters, keys, Clock.systemUTC(),
                    JSON, new TopicRouteConfigValidator(), mock(CmsTopicMapper.class));
            Method freeze = type.getDeclaredMethod("freezePublication", CmsTopic.class, String.class);
            freeze.setAccessible(true);
            return (String) freeze.invoke(compiler, topic, null);
        }
    }
}
