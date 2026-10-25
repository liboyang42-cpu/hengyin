package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.support.TopicStateKeyRefService;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 发布即生成活动；草稿保持私有，后审回调幂等。 */
class TopicPendingReviewSelfHostShellTest {

    private static final Long TOPIC = 970001L;
    private static final Long OWNER = 42L;

    private CmsTopicServiceImpl svc;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;

    @BeforeEach
    void setUp() {
        svc = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        // publishDraft 途径的协作者很多且与本测试无关:全量置 mock,再对断言目标取回引用。
        for (Field f : CmsTopicServiceImpl.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            try {
                if (f.get(svc) == null) {
                    ReflectionTestUtils.setField(svc, f.getName(), Mockito.mock(f.getType()));
                }
            } catch (Exception ignore) {
            }
        }
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wireRelocatedCollaborators(svc, publicationField -> org.mockito.Mockito.mock(publicationField.getType()));
        topicMapper = (CmsTopicMapper) ReflectionTestUtils.getField(svc, "cmsTopicMapper");
        activityMapper = (CmsActivityMapper) ReflectionTestUtils.getField(svc, "cmsActivityMapper");
        // CORE OWN-C03-15：自办活动插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        org.mockito.Mockito.lenient().when(activityMapper.insertCmsActivity(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsActivity) inv.getArgument(0)).setId(880001L);
            return 1;
        });
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
        // R19-DRAFT2/状态键闸随旅程分支合入后,publishDraft 会走引用收集 —— 自动 mock 的
        // TopicStateKeyRefService 返回 null 会直接 NPE,这里换成真实实现(三个 mapper 空集合即可)。
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "topicStateKeyRefService",
                new TopicStateKeyRefService(
                        (CmsTopicNodeMapper) ReflectionTestUtils.getField(svc, "cmsTopicNodeMapper"),
                        (CmsMemberTemplateMapper) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "cmsMemberTemplateMapper"),
                        (CmsTopicChapterMapper) ReflectionTestUtils.getField(svc, "cmsTopicChapterMapper")));
        when(topicMapper.clearDraftIfDraft(TOPIC)).thenReturn(1);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(eq(TOPIC), any(), any(Date.class))).thenReturn(1);
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.<CmsActivity>emptyList());
        com.chengyinhub.business.service.support.TopicBundleReviewService bundles =
                (com.chengyinhub.business.service.support.TopicBundleReviewService)
                        ReflectionTestUtils.getField(svc, "topicBundleReviewService");
        when(bundles.freezeForSubmission(TOPIC, OWNER)).thenReturn(
                new com.chengyinhub.business.service.support.TopicBundleReviewService.Submission(
                        "{\"schemaVersion\":2}", Collections.emptyList()));
        IAuditTaskService audits = (IAuditTaskService) ReflectionTestUtils.getField(svc, "auditTaskService");
        when(audits.submitOrRefreshTopicBundle(eq(TOPIC), eq(OWNER), any(String.class))).thenReturn(99L);
        com.chengyinhub.business.domain.AuditTask task = new com.chengyinhub.business.domain.AuditTask();
        task.setId(99L);
        task.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        when(audits.selectAuditTaskById(99L)).thenReturn(task);
        wirePublicationContent();
        ReflectionTestUtils.setField(svc, "themeStateKeyService", new com.chengyinhub.business.service.support.ThemeStateKeyService(
                Mockito.mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        svc = com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(svc);
    }

    private CmsTopic topic(Integer status, String publishMode) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setName("夜跑城市定向");
        topic.setPublishMode(publishMode);
        topic.setMemberId(OWNER);
        topic.setCreateBy("publisher");
        topic.setStatus(status);
        topic.setUserStatus(status != null && status == 1 ? 1 : 0);
        return topic;
    }

    /** Current reads see the same row until the original publication update actually writes it. */
    private void publicationRows(CmsTopic before, CmsTopic after) {
        java.util.concurrent.atomic.AtomicReference<CmsTopic> current = new java.util.concurrent.atomic.AtomicReference<>(before);
        org.mockito.Mockito.doAnswer(call -> current.get()).when(topicMapper).selectCmsTopicById(TOPIC);
        org.mockito.Mockito.doAnswer(call -> {
            CmsTopic patch = call.getArgument(0);
            if (patch.getStatus() != null && patch.getUserStatus() != null) {
                assertEquals(after.getStatus(), patch.getStatus());
                assertEquals(after.getUserStatus(), patch.getUserStatus());
            }
            CmsTopic stored=current.get();
            if(stored==null || !TOPIC.equals(patch.getId()))return 0;
            var source=new org.springframework.beans.BeanWrapperImpl(patch);
            var target=new org.springframework.beans.BeanWrapperImpl(stored);
            for(var property:source.getPropertyDescriptors()) {
                String name=property.getName();
                if(source.isReadableProperty(name) && target.isWritableProperty(name)
                        && source.getPropertyValue(name)!=null)target.setPropertyValue(name,source.getPropertyValue(name));
            }
            return 1;
        }).when(topicMapper).updateCmsTopic(any(CmsTopic.class));
    }

    /** 专业版上架与后台待审是独立事实。 */
    @Test
    void proDraftPublish_whilePendingReview_createsPublicShell() {
        CmsTopic pending = topic(0, "pro");
        // 发布前读取草稿，写入后重读已上架状态。
        publicationRows(pending, topic(1, "pro"));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(TOPIC));

        ArgumentCaptor<CmsTopic> topicCap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(topicCap.capture());
        assertEquals(Integer.valueOf(1), topicCap.getValue().getStatus(), "pro 发布即上架");
        assertEquals(Integer.valueOf(0), topicCap.getValue().getCheckStatus());

        ArgumentCaptor<CmsActivity> activityCap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper, atMostOnce()).insertCmsActivity(activityCap.capture());
        assertEquals(1, activityCap.getAllValues().size(), "先上后审必须同步生成可玩的活动");
        assertRealPublishedContent();
    }

    /** 正面对照:简易版免审直发(status=1)照旧建公开壳,审核通过后的售卖面不能断。 */
    @Test
    void simpleDraftPublish_stillCreatesPublicShell() {
        CmsTopic draft = topic(0, "simple");
        CmsTopic published = topic(1, "simple");
        // 第一次读 = 草稿;第二次读(ensure 重读)= 发布后已上架(status=1)
        publicationRows(draft, published);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        assertTrue(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(TOPIC));

        ArgumentCaptor<CmsTopic> topicCap = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicMapper).updateCmsTopic(topicCap.capture());
        assertEquals(Integer.valueOf(1), topicCap.getValue().getStatus(), "简易版发布 = 直接上架");

        CmsActivity shell = capturedShell();
        assertEquals(Long.valueOf(1L), shell.getStatus(), "壳必须上架");
        assertEquals(Long.valueOf(1L), shell.getPublishStatus(), "壳必须已发布");
        assertEquals(Integer.valueOf(0), shell.getDelFlag());
        assertEquals(TOPIC, shell.getTopicId());
        assertEquals(OWNER, shell.getMemberId(), "自办壳承接方 = 发布者本人");
        assertRealPublishedContent();
    }

    /** 审核通过回调:补建壳一次;重复回调(幂等)不再建第二个壳。 */
    @Test
    void approvedTopic_ensureShell_isIdempotent() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(1, "pro"));
        CmsActivity existing = new CmsActivity();
        existing.setId(500L);
        existing.setTopicId(TOPIC);
        existing.setMemberId(OWNER);
        // 第一次探针空(缺壳)→ 建;第二次探针命中已建壳 → 不建
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Collections.<CmsActivity>emptyList())
                .thenReturn(Arrays.asList(existing));

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);

        verify(activityMapper, times(1)).insertCmsActivity(any(CmsActivity.class));
    }

    @Test
    void explicitMemberOnlySelfHostShellCarriesItsActualClubScope() {
        CmsTopic memberOnly = topic(1, "pro"); memberOnly.setClubMemberOnly(1); memberOnly.setClubId(81L);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(memberOnly);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);
        assertEquals(Long.valueOf(81L), capturedShell().getClubId());
    }

    @Test
    void publicClubSelfHostTopicsRemainPublicForBothExplicitAndLegacyFlags() {
        for (Integer flag : new Integer[] {0, null}) {
            org.mockito.Mockito.clearInvocations(activityMapper);
            CmsTopic publicTopic = topic(1, "pro"); publicTopic.setClubMemberOnly(flag); publicTopic.setClubId(81L);
            when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(publicTopic);
            com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);
            org.junit.jupiter.api.Assertions.assertNull(capturedShell().getClubId());
        }
    }

    @Test
    void memberOnlyWithoutActualClubCannotSilentlyCreateAPublicShellOrCopyTickets() {
        for (Long clubId : new Long[] {null, 0L, -1L}) {
            CmsTopic invalid = topic(1, "pro"); invalid.setClubMemberOnly(1); invalid.setClubId(clubId);
            when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(invalid);
            ServiceException error = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC));
            assertEquals("成员限定主题的俱乐部资料不完整，无法生成场次", error.getMessage());
        }
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
        IOmsTicketService tickets = (IOmsTicketService) com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc, "omsTicketService");
        verify(tickets, never()).copyTopicTicketsToActivity(anyLong(), anyLong(), anyLong());
    }

    /** 待审(status=0):无论调用几次补建口,都不许出壳。 */
    @Test
    void pendingTopic_ensureShell_neverBuilds() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(0, "pro"));

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);

        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /** 驳回(status=2):同样不许出壳。 */
    @Test
    void rejectedTopic_ensureShell_neverBuilds() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(2, "pro"));

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);

        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /** 负控:进商家池的主题不由发布者自办,过审也不建自办壳(壳由中标流程生成)。 */
    @Test
    void merchantPoolTopic_ensureShell_neverBuilds() {
        CmsTopic pool = topic(1, "pro");
        pool.setMerchantStatus(1);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(pool);

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);

        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /** 负控:已有壳(含商家壳)一律不重复建。 */
    @Test
    void existingShell_ensureShell_neverBuilds() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(1, "pro"));
        CmsActivity existing = new CmsActivity();
        existing.setId(500L);
        when(activityMapper.selectCmsActivityList(any(CmsActivity.class)))
                .thenReturn(Arrays.asList(existing));

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);

        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    // ——— release-0917 同步 master(#1078)× rule-funds 欠款逾期闸,总控裁定 C ———
    // #1078 把 pro 主题的自办活动壳挪到审核通过回调;欠款逾期闸原本在 generateSelfHostActivity 开头。
    // 裁定:提交口(建题 / publishDraft)就拦;审核回调不抛(否则回滚 review() 审核结论),这次不建壳,欠款补足后刷新补建。

    private SettlementDebtLedger ledger() {
        return (SettlementDebtLedger) ReflectionTestUtils.getField(svc, "settlementDebtLedger");
    }

    /** 提交口:欠款逾期的主理人发布草稿,在提交时就被拦,主题状态不动。 */
    @Test
    void proDraftPublish_withOverdueDebt_isRejectedAtSubmission() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(0, "pro"));
        doThrow(new ServiceException("SETTLEMENT_DEBT_OVERDUE:你有逾期未补足的欠款,暂不能发布新活动,请联系平台客服处理"))
                .when(ledger()).requireNoOverdueDebt(OWNER);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(svc);
        ServiceException e = assertThrows(ServiceException.class, () -> com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(svc).publishDraft(TOPIC));

        assertTrue(e.getMessage().startsWith("SETTLEMENT_DEBT_OVERDUE"), e.getMessage());
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /**
     * 审核期间才变逾期:审核通过回调不抛(不把 review() 的审核结论回滚)、这次不建壳、也不走会抛的闸。
     * 负控:去掉 ensureSelfHostActivityShell(Long) 里的 hasOverdueDebt 判断 → 建壳路径会被调用(见下一条用 requireNoOverdueDebt 抛出复现)。
     */
    @Test
    void approvedTopic_becameOverdueDuringReview_doesNotThrowAndBuildsNoShell() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(1, "pro"));
        when(ledger().hasOverdueDebt(OWNER)).thenReturn(true);
        doThrow(new ServiceException("SETTLEMENT_DEBT_OVERDUE:你有逾期未补足的欠款,暂不能发布新活动,请联系平台客服处理"))
                .when(ledger()).requireNoOverdueDebt(OWNER);

        assertDoesNotThrow(() -> com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC),
                "审核通过回调在 review() 事务里,抛出会把审核结论一起回滚");

        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));
    }

    /** 欠款补足后:下次编辑触发的 refreshSelfHostActivity 补建自办活动壳。 */
    @Test
    void debtClearedAfterApproval_refreshBuildsTheMissingShell() {
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(1, "pro"));
        when(ledger().hasOverdueDebt(OWNER)).thenReturn(true, false);

        com.chengyinhub.business.service.support.TopicPublicationTestWiring.wire(svc).ensureSelfHostActivityShell(TOPIC);
        verify(activityMapper, never()).insertCmsActivity(any(CmsActivity.class));

        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(svc, "refreshSelfHostActivity"), "refreshSelfHostActivity", TOPIC, OWNER, "publisher");

        CmsActivity shell = capturedShell();
        assertEquals(TOPIC, shell.getTopicId());
        assertEquals(OWNER, shell.getMemberId());
    }

    private CmsActivity capturedShell() {
        ArgumentCaptor<CmsActivity> cap = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).insertCmsActivity(cap.capture());
        return cap.getValue();
    }

    private static String describePublicShell(ArgumentCaptor<CmsActivity> cap) {
        if (cap.getAllValues().isEmpty()) {
            return "(无壳)";
        }
        CmsActivity a = cap.getValue();
        return "status=" + a.getStatus() + ", publishStatus=" + a.getPublishStatus()
                + ", delFlag=" + a.getDelFlag() + ", topicId=" + a.getTopicId();
    }

    /** Same current chapter/node/template facts feed the guards and the real publication compiler. */
    private void wirePublicationContent() {
        var chapters=(CmsTopicChapterMapper)ReflectionTestUtils.getField(svc,"cmsTopicChapterMapper");
        var nodes=(CmsTopicNodeMapper)ReflectionTestUtils.getField(svc,"cmsTopicNodeMapper");
        var templates=(CmsMemberTemplateMapper)com.chengyinhub.business.service.support.TopicPublicationTestWiring.get(svc,"cmsMemberTemplateMapper");
        var chapter=new com.chengyinhub.business.domain.CmsTopicChapter();
        chapter.setId(970011L);chapter.setTopicId(TOPIC);chapter.setName("入场核对");chapter.setSortId(1L);chapter.setRequired(1);
        var node=new com.chengyinhub.business.domain.CmsTopicNode();
        node.setId(970021L);node.setTopicId(TOPIC);node.setChapterId(chapter.getId());node.setTemplateId(970031L);
        node.setStatus(1L);node.setName("营业时间核对");node.setSortId(1L);
        var template=new com.chengyinhub.business.domain.CmsMemberTemplate();
        template.setId(970031L);template.setMemberId(OWNER);template.setTitle("营业时间核对");
        template.setStatus(1L);template.setDraftStatus(1);template.setPublishStatus(1L);template.setCheckStatus(1);template.setDelFlag(0);
        template.setValidationMethod(1);template.setQuestionName("场地何时开放？");template.setQuestionAnswer("18:00");
        template.setAdvancedConfigJson(new com.chengyinhub.business.service.support.AdvancedGameConfigValidator().validateAndNormalize(
                "{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"TYPE\",\"title\":\"场地何时开放？\",\"answerText\":\"18:00\",\"maxTries\":0,\"xp\":0}}"));
        Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicChapter query=call.getArgument(0);
            return query!=null && TOPIC.equals(query.getTopicId()) ? java.util.List.of(chapter) : java.util.List.of();
        }).when(chapters).selectCmsTopicChapterList(any());
        Mockito.doAnswer(call -> chapter.getId().equals(call.getArgument(0)) ? chapter : null)
                .when(chapters).selectCmsTopicChapterById(anyLong());
        Mockito.doAnswer(call -> {
            com.chengyinhub.business.domain.CmsTopicNode query=call.getArgument(0);
            return query!=null && TOPIC.equals(query.getTopicId())
                    && (query.getStatus()==null || query.getStatus().equals(node.getStatus()))
                    ? java.util.List.of(node) : java.util.List.of();
        }).when(nodes).selectCmsTopicNodeList(any());
        Mockito.doAnswer(call -> node.getId().equals(call.getArgument(0)) ? node : null)
                .when(nodes).selectCmsTopicNodeById(anyLong());
        Mockito.doAnswer(call -> template.getId().equals(call.getArgument(0)) ? template : null)
                .when(templates).selectCmsMemberTemplateById(anyLong());
        Mockito.doAnswer(call -> {
            java.util.List<Long> ids=call.getArgument(0);
            return ids.contains(template.getId()) ? java.util.List.of(template) : java.util.List.of();
        }).when(templates).selectCmsMemberTemplateByIds(org.mockito.ArgumentMatchers.anyList());
        Mockito.doAnswer(call -> {
            CmsTopic row=topicMapper.selectCmsTopicById(call.getArgument(0));if(row==null)return 0;
            String snapshot=call.getArgument(1);assertTrue(snapshot!=null && !snapshot.isBlank());
            row.setPublishedRouteGraphJson(snapshot);
            row.setPublishedConfigVersion(row.getPublishedConfigVersion()==null ? 1L : row.getPublishedConfigVersion()+1);
            return 1;
        }).when(topicMapper).publishRouteSnapshot(anyLong(),any(),any(Date.class));
    }

    private void assertRealPublishedContent() {
        CmsTopic row=topicMapper.selectCmsTopicById(TOPIC);
        var snapshot=org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getPublishedRouteGraphJson()));
        assertEquals(1,snapshot.path("runtimeSnapshotVersion").asInt());
        assertEquals(1,snapshot.path("stateKeySnapshotVersion").asInt());
        var content=snapshot.path("nodeAnswers").path("970021");
        assertEquals(970031L,content.path("templateId").asLong());
        assertEquals(TOPIC.longValue(),content.path("nodePresentation").path("topicId").asLong());
        assertEquals("18:00",content.path("questionAnswer").asText());
    }
}
