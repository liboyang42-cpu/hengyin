package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
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
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard.PublicationProblem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 冻结预演对着**真冻结算子**跑:证明「一次性 CmsTopic 副本 + 只读上下文」足以覆盖
 * 线性/旅程冻结期的错误,且全程不写库、不取 FOR UPDATE、不改调用方实体。
 *
 * <p>这不是推断:{@code TopicRoutePublicationCompiler#withCurrentContent} 在无活动事务时直接执行
 * operation(不调 {@code selectCmsTopicByIdForUpdate} 一族),{@code freezeCurrentPublication}
 * 自己 copy 一份 topic 再写 {@code themeRuntimeConfigJson}。本用例把这两条钉成断言。
 *
 * <p>负控:把 {@link TopicPublicationContentGuard#assertFreezePreview} 改成「不复制、直接冻结」,
 * {@code callerTopicKeepsItsOwnRuntimeConfig} 红;改成用 3 参 MANDATORY 绑定,
 * {@code previewFreezeTakesNoLocksAndWritesNothing} 红(会抛「正式发布必须加入主题写事务」)。
 */
class TopicPublicationFreezePreviewReadOnlyTest {

    private static final long TOPIC_ID = 10L;
    private static final long NODE_ID = 11L;
    private static final long TEMPLATE_ID = 701L;
    /** 作者原文:最小合法运行配置。 */
    private static final String AUTHORED_CONFIG = "{\"schemaVersion\":1}";
    /** 编译器产出:与作者原文不同但同样合法,用来证明写回落在副本上。 */
    private static final String COMPILED_CONFIG = "{\"schemaVersion\":1,\"capacity\":0}";

    private CmsTopicMapper topicMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsMemberTemplateMapper templateMapper;
    private ThemeStateKeyMapper keyMapper;
    private TopicRoutePublicationCompiler compiler;
    private CmsTopic topic;
    private TopicPublicationContentGuard guard;
    /** 预演端口实际收到的声明投影(用于证明 B3 把「登记之后」的集合交给了冻结)。 */
    private List<com.chengyinhub.business.domain.ThemeStateKey> capturedDeclarations;

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
        // 必须是 parseDraft 能接受的最小合法配置:预演会真的走 ThemeRuntimeConfig.parseDraft。
        topic.setThemeRuntimeConfigJson(AUTHORED_CONFIG);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setValidationMethod(1);

        lenient().when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        lenient().when(nodeMapper.selectCmsTopicNodeList(any()))
                .thenReturn(Collections.singletonList(node));
        lenient().when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template));
        lenient().when(templateMapper.selectCmsMemberTemplateById(any()))
                .thenReturn(template);
        lenient().when(keyMapper.selectByTopicId(any())).thenReturn(Collections.emptyList());
        lenient().when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        compiler = new TopicRoutePublicationCompiler(nodeMapper, templateMapper, chapterMapper, keyMapper,
                Clock.systemUTC(), new ObjectMapper(), new TopicRouteConfigValidator(), topicMapper);
        // 只把编译与预演两端口接到真算子上:其余运行期能力与本预演无关。
        // previewPublication 用真编译器 + 观察到的声明投影,等价于 B4 交付后的语义
        // (临时 CurrentContent + 注入声明,只读、不写库、不取行锁)。
        // 同 NoEarlyExit:接口抽象方法很多,匿名实现只会制造无关编译错误。改 mock 只接两个端口。
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        // 故意编译出与作者原文不同的合法配置:若预演没有用一次性副本,
        // 这个写回会污染调用方实体,callerTopicKeepsItsOwnRuntimeConfig 立刻红。
        lenient().when(runtime.compileTemplateRules(any())).thenReturn(COMPILED_CONFIG);
        lenient().when(runtime.previewPublication(any(), any(), any())).thenAnswer(inv -> {
            capturedDeclarations = inv.getArgument(2);
            // 真算子的只读预演入口(B4 提供):普通读 + 一次性内容投影 + finally 还原上下文。
            return compiler.previewPublication(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        });

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

    /** 线性旅程主题:预演真的跑完编译 + 冻结,全程零锁零写。 */
    @Test
    void previewFreezeTakesNoLocksAndWritesNothing() {
        guard.assertFreezePreview(topic);

        assertNoRowLocks(topicMapper, "CmsTopicMapper");
        assertNoRowLocks(nodeMapper, "CmsTopicNodeMapper");
        assertNoRowLocks(chapterMapper, "CmsTopicChapterMapper");
        assertNoRowLocks(templateMapper, "CmsMemberTemplateMapper");
        assertNoRowLocks(keyMapper, "ThemeStateKeyMapper");
        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(any());
        verify(chapterMapper, never()).selectByTopicForUpdate(any());
        verify(nodeMapper, never()).selectByTopicForUpdate(any());
        verify(templateMapper, never()).selectCmsMemberTemplateByIdsForUpdate(any());
        verify(keyMapper, never()).lockTopicForStateKeyWrite(any());
    }

    /** 副本隔离:编译结果只落在一次性副本上,调用方实体的作者配置一字不变。 */
    @Test
    void callerTopicKeepsItsOwnRuntimeConfig() {
        guard.assertFreezePreview(topic);

        assertEquals(AUTHORED_CONFIG, topic.getThemeRuntimeConfigJson(),
                "编译产出必须落在一次性副本上,不能污染调用方实体");
    }
    /** 只读事务里同样可以预演(编译器的当前读分支只认「活动且可写」的事务)。 */
    @Test
    void previewRunsInsideAReadOnlyTransactionToo() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        guard.assertFreezePreview(topic);

        verify(topicMapper, never()).selectCmsTopicByIdForUpdate(any());
    }

    /** 收集器整条链路也零锁零写(冻结预演在内)。 */
    @Test
    void collectorKeepsTheWholePreviewReadOnly() {
        List<PublicationProblem> problems = guard.collectPublicationProblems(topic);

        assertFalse(problems.stream().anyMatch(p -> PublicationProblem.CODE_FREEZE_INVALID.equals(p.getCode())),
                problems.toString());
        assertNoRowLocks(topicMapper, "CmsTopicMapper");
        assertNoRowLocks(nodeMapper, "CmsTopicNodeMapper");
        assertNoRowLocks(chapterMapper, "CmsTopicChapterMapper");
        assertNoRowLocks(templateMapper, "CmsMemberTemplateMapper");
        assertNoRowLocks(keyMapper, "ThemeStateKeyMapper");
        assertEquals(AUTHORED_CONFIG, topic.getThemeRuntimeConfigJson());
    }

    private static void assertNoRowLocks(Object mapper, String name) {
        for (Invocation invocation : mockingDetails(mapper).getInvocations()) {
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
