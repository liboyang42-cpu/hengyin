package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 发布前试检查的规则投影:真实发布闸会拦的每一类问题,预检都要提前列出,并且能定位到章节/块/字段。
 *
 * <p>为什么需要这一份:改造前预检只覆盖状态键 / 块配置 / 数值规则,内容完整度、路线图、剧情引用、
 * 运行配置引用、叙事 NPC、块内 sys.* 一律不查 —— 「预检通过、发布被拦」就是这么来的。
 */
class TopicPublishCheckRuleProjectionTest {

    private static final long TOPIC_ID = 21L;
    private static final long CHAPTER_ID = 101L;
    private static final long NODE_ID = 301L;
    private static final long TEMPLATE_ID = 401L;

    private CmsTopicNodeMapper nodeMapper;
    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicChapterMapper chapterMapper;
    private OmsTicketMapper ticketMapper;
    private ThemeStateKeyMapper keyMapper;
    private TopicBundleReviewService bundleService;
    private TopicPublishCheckService service;

    @BeforeEach
    void setUp() {
        nodeMapper = mock(CmsTopicNodeMapper.class);
        templateMapper = mock(CmsMemberTemplateMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        keyMapper = mock(ThemeStateKeyMapper.class);
        bundleService = mock(TopicBundleReviewService.class);
        when(bundleService.freezeForSubmission(TOPIC_ID, 7L))
                .thenReturn(new TopicBundleReviewService.Submission("{}", Collections.emptyList()));

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(TOPIC_ID);
        node.setChapterId(CHAPTER_ID);
        node.setName("书房");
        node.setTemplateId(TEMPLATE_ID);
        node.setStatus(1L);
        lenient().when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Collections.singletonList(node));
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER_ID);
        chapter.setTopicId(TOPIC_ID);
        chapter.setName("第一章");
        lenient().when(chapterMapper.selectCmsTopicChapterList(any(CmsTopicChapter.class)))
                .thenReturn(Collections.singletonList(chapter));
        lenient().when(ticketMapper.selectOmsTicketList(any())).thenReturn(Collections.emptyList());

        TopicPublicationContentGuard guard = new TopicPublicationContentGuard();
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
        com.chengyinhub.business.service.ITopicRouteRuntimeService runtime =
                mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
        lenient().when(runtime.previewPublication(any(CmsTopic.class), any(), any()))
                .thenReturn("{\"runtimeSnapshotVersion\":1}");
        ReflectionTestUtils.setField(guard, "topicRouteRuntimeService", runtime);

        service = new TopicPublishCheckService(nodeMapper, templateMapper,
                new ThemeStateKeyService(keyMapper),
                new TopicStateKeyRefService(nodeMapper, templateMapper, chapterMapper),
                bundleService, chapterMapper, guard);
    }

    /**
     * 块内 NODE_COMPLETED 引用到主题外的节点:发布闸会拦,预检提前报出,并带上
     * 章节 id / 章节名 / 节点名 / 玩法块 / 字段路径。
     *
     * <p>同时必须出现发布闸自己的系统变量规则(同一句文案):预检若只报答题器口径,
     * 作者修好一处仍会在发布时被另一处拦。
     */
    @Test
    void publicationRuleFailureIsLocatedToChapterNodeAndField() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");

        List<PublishCheckIssue> issues = service.check(topic());

        PublishCheckIssue located = issues.stream()
                .filter(issue -> TopicPublicationContentGuard.PublicationProblem.CODE_BLOCK_SYS_VAR_INVALID
                        .equals(issue.getCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少发布闸的系统变量问题:" + issues));
        assertEquals(NODE_ID, located.getNodeId());
        assertEquals("nodes[nodeId=" + NODE_ID + "].template[templateId=" + TEMPLATE_ID + "].advancedConfigJson",
                located.getField());
        assertTrue(located.getMessage().contains("NODE_COMPLETED"), located.getMessage());

        // 章节 / 节点名定位由预检自己补齐(发布闸只给 nodeId)。
        PublishCheckIssue checksumLocated = first(issues, TopicPublishCheckService.CODE_BLOCK_CONFIG_INVALID);
        assertEquals(CHAPTER_ID, checksumLocated.getChapterId());
        assertEquals("第一章", checksumLocated.getChapterTitle());
        assertEquals("书房", checksumLocated.getNodeName());
    }

    /**
     * 未注册状态键只报一条:预检的逐键投影与发布闸的注册闸是同一份引用集合,
     * 两处都出项就会给同一颗键报两行。
     * 负控:删掉 appendPublicationRuleIssues 里的 CODE_STATE_KEY_UNREGISTERED 跳过,本用例红。
     */
    @Test
    void unregisteredStateKeyIsReportedOnce() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"var\":\"counter.leak\",\"op\":\"EQ\",\"value\":1}]}");

        List<PublishCheckIssue> issues = service.check(topic());

        List<PublishCheckIssue> duplicates = issues.stream()
                .filter(issue -> TopicPublishCheckService.CODE_STATE_KEY_UNREGISTERED.equals(issue.getCode()))
                .toList();
        assertEquals(1, duplicates.size(), issues.toString());
        assertTrue(duplicates.get(0).getMessage().contains("counter.leak"), duplicates.get(0).getMessage());
        assertEquals(NODE_ID, duplicates.get(0).getNodeId());
        assertEquals("stateKeys", duplicates.get(0).getField());
    }

    /**
     * 预检是只读投影:任何 mapper 上都不许出现写调用,也不许出现 FOR UPDATE 当前读 ——
     * 作者点一次「发布前检查」不能把写链路的行锁队列堵住(I 预审同口径)。
     *
     * <p>负控:把收集器里的尺寸检查换回 {@code assertTopicSizeWithinLimits}(走
     * {@code lockTopicContentBudget}),本用例红。
     */
    @Test
    void checkNeitherWritesNorTakesRowLocks() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");

        service.check(topic());

        assertReadOnly(nodeMapper, "CmsTopicNodeMapper");
        assertReadOnly(templateMapper, "CmsMemberTemplateMapper");
        assertReadOnly(chapterMapper, "CmsTopicChapterMapper");
        assertReadOnly(ticketMapper, "OmsTicketMapper");
        assertReadOnly(keyMapper, "ThemeStateKeyMapper");
    }

    @Test void missingAssetDeclarationIsASaveWarningFromTheOriginalMediaOwner() {
        AppMediaPublicationGuard media=mock(AppMediaPublicationGuard.class);
        when(media.missingTopicLicenses(any(CmsTopic.class))).thenReturn(java.util.Set.of("media-asset:7"));
        ReflectionTestUtils.setField(service,"mediaPublicationGuard",media);
        TopicPublishCheckService.PublishCheckIssue issue=service.check(topic()).stream()
                .filter(row->AppMediaProvenance.LICENSE_MISSING.equals(row.getCode())).findFirst().orElseThrow();
        assertEquals("warn",issue.getLevel());assertEquals("mediaAsset",issue.getField());
        assertTrue(issue.getMessage().contains("media-asset:7"));
        verify(media).missingTopicLicenses(any(CmsTopic.class));
    }

    @Test
    void formalRuleFailureCarriesTheSameCodeAndFieldAsRepeatedPrechecks() {
        stubTemplate("{\"schemaVersion\":1,\"requires\":[{\"op\":\"NODE_COMPLETED\",\"nodeId\":9999}]}");
        List<PublishCheckIssue> firstCheck = service.check(topic());
        List<PublishCheckIssue> secondCheck = service.check(topic());
        TopicPublicationContentGuard guard = (TopicPublicationContentGuard) ReflectionTestUtils.getField(service, "publicationContentGuard");
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> guard.assertBlockSysVarReferences(topic()));
        var formal = com.chengyinhub.business.exception.TopicPublicationContentException.dataOf(failure).getIssues().get(0);
        for (List<PublishCheckIssue> checked : java.util.List.of(firstCheck, secondCheck)) {
            PublishCheckIssue preview = first(checked, formal.getCode());
            assertEquals(preview.getNodeId(), formal.getNodeId());
            assertEquals(preview.getField(), formal.getField());
        }
        assertReadOnly(nodeMapper, "CmsTopicNodeMapper");
        assertReadOnly(templateMapper, "CmsMemberTemplateMapper");
        assertReadOnly(chapterMapper, "CmsTopicChapterMapper");
        assertReadOnly(ticketMapper, "OmsTicketMapper");
        assertReadOnly(keyMapper, "ThemeStateKeyMapper");
    }

    @Test
    void registeredMediaUsesTheSameDeclarationLevelAndLocationAsFormalPublication() {
        var rows = mock(com.chengyinhub.business.mapper.AppMediaAssetMapper.class);
        var row = new java.util.HashMap<String,Object>(java.util.Map.of("id",7L,"memberId",7L,"ownerType","APP", "reviewState","APPROVED", "sourceType","unknown"));
        org.mockito.Mockito.when(rows.select(7L)).thenReturn(row);
        AppMediaReferenceGuard references = new AppMediaReferenceGuard();
        ReflectionTestUtils.setField(references,"mapper",rows);
        AppMediaPublicationGuard media = new AppMediaPublicationGuard();
        ReflectionTestUtils.setField(media,"references",references);
        ReflectionTestUtils.setField(media,"nodes",nodeMapper);
        ReflectionTestUtils.setField(media,"chapters",chapterMapper);
        ReflectionTestUtils.setField(media,"templates",templateMapper);
        ReflectionTestUtils.setField(service,"mediaPublicationGuard",media);
        CmsTopic topic = topic(); topic.setImgUrl("media-asset:7");
        var preview = first(service.check(topic),AppMediaProvenance.LICENSE_MISSING);
        var failure = org.junit.jupiter.api.Assertions.assertThrows(com.chengyinhub.common.exception.ServiceException.class,
                () -> media.requireMedia(7L,"media-asset:7",true));
        var formal = com.chengyinhub.business.exception.TopicPublicationContentException.dataOf(failure).getIssues().get(0);
        assertEquals(preview.getCode(),formal.getCode());
        assertEquals(preview.getLevel(),formal.getLevel());
        assertEquals("error",preview.getLevel());
        assertEquals(preview.getNodeId(),formal.getNodeId());
        assertEquals(preview.getField(),formal.getField());
        row.remove("sourceType");
        assertEquals("warn",first(service.check(topic),AppMediaProvenance.LICENSE_MISSING).getLevel());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> media.requireMedia(7L,"media-asset:7",true));
        assertReadOnly(rows,"AppMediaAssetMapper");
    }

    private void stubTemplate(String advancedConfigJson) {
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(TEMPLATE_ID);
        template.setMemberId(7L);
        template.setAdvancedConfigJson(advancedConfigJson);
        lenient().when(templateMapper.selectCmsMemberTemplateById(TEMPLATE_ID)).thenReturn(template);
        lenient().when(templateMapper.selectCmsMemberTemplateByIds(any()))
                .thenReturn(Collections.singletonList(template));
    }

    private static PublishCheckIssue first(List<PublishCheckIssue> issues, String code) {
        return issues.stream().filter(issue -> code.equals(issue.getCode())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少 " + code + " 问题:" + issues));
    }

    private static void assertReadOnly(Object mapper, String name) {
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
