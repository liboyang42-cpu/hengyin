package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.ValidationMethod;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CmsTopicRoutePublishPolicyTest {

    private static CmsTopicServiceImpl publication(CmsTopicServiceImpl service) {
        // Complete the original fixture ports before the real publication owner is invoked.
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "themeStateKeyService",
                new com.chengyinhub.business.service.support.ThemeStateKeyService(
                        mock(com.chengyinhub.business.mapper.ThemeStateKeyMapper.class)));
        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service);
        return com.chengyinhub.business.service.support.TopicPublicationTestWiring.transactional(service);
    }

    private static void stubLockedTopicRead(CmsTopicMapper topicMapper) {
        when(topicMapper.selectCmsTopicByIdForUpdate(anyLong()))
                .thenAnswer(inv -> topicMapper.selectCmsTopicById(inv.getArgument(0)));
    }

    @Test
    void publishDraftRejectsBranchGraphForNonOrienteeringTopic() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        stubLockedTopicRead(topicMapper);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        // R19-DRAFT2:发布闸要读票种(完整度),不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        when(topicMapper.clearDraftIfDraft(10L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(100L); topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setRouteMode("BRANCH_GRAPH"); topic.setPublishMode("simple");
        // R19-DRAFT2 的发布完整度闸(名称/招募截止)先过,才能测到路线模式这道闸。
        topic.setName("夜跑路线");
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 86400000L));
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertThrows(IllegalArgumentException.class, () -> publication(service).publishDraft(10L));
    }

    @Test
    void publishDraftRejectsAnEdgeWithoutADeterministicReachabilityFixture() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        stubLockedTopicRead(topicMapper);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        when(topicMapper.clearDraftIfDraft(10L)).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(100L); topic.setProductType(ProductType.ORIENTEERING);
        topic.setRouteMode("BRANCH_GRAPH"); topic.setPublishMode("simple");
        topic.setName("夜跑路线");
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":1,\"terminalNodeIds\":[2,3]," +
                "\"variables\":{\"score\":0},\"edges\":[{" +
                "\"id\":\"impossible\",\"fromNodeId\":1,\"toNodeId\":2," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}," +
                "\"conditions\":[{\"op\":\"GT\",\"var\":\"score\",\"value\":5}," +
                "{\"op\":\"LT\",\"var\":\"score\",\"value\":3}]}]," +
                "\"fallbacks\":[{\"fromNodeId\":1,\"toNodeId\":3}]}");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, null), node(2L, null), node(3L, null)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> publication(service).publishDraft(10L));

        assertEquals("路线 edge impossible 没有确定性可达状态夹具 [path=routeGraphJson.edges[0], fromNodeId=1, toNodeId=2]",
                error.getCause().getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("path=routeGraphJson"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("edge impossible"));
        verify(topicMapper, never()).updateCmsTopic(any(CmsTopic.class));
        verify(topicMapper, never()).publishRouteSnapshot(any(Long.class), any(String.class), any(java.util.Date.class));
    }

    @Test
    void successfulPublishCopiesTheNormalizedDraftIntoThePublishedSnapshot() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        stubLockedTopicRead(topicMapper);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        // R18-DRAFT:发布时补建自办活动壳 —— 成功路径会走到这两个协作者,不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "cmsActivityMapper",
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        // release-0917 同步 master:rule-funds 欠款逾期闸在 publishDraft 提交口与建壳处,手工装配须补上;mock 默认无逾期。
        ReflectionTestUtils.setField(service, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        when(topicMapper.clearDraftIfDraft(10L)).thenReturn(1);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        when(topicMapper.publishRouteSnapshot(eq(10L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(100L); topic.setProductType(ProductType.ORIENTEERING);
        topic.setRouteMode("BRANCH_GRAPH"); topic.setPublishMode("simple");
        topic.setName("夜跑路线");
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":\"1\",\"terminalNodeIds\":[\"2\"]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":\"1\",\"toNodeId\":\"2\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, null), node(2L, null)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertEquals(true, publication(service).publishDraft(10L));

        org.mockito.ArgumentCaptor<String> snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(topicMapper).publishRouteSnapshot(eq(10L), snapshot.capture(), any(java.util.Date.class));
        assertEquals(true, snapshot.getValue().contains("\"startNodeId\":1"));
        assertEquals(false, snapshot.getValue().contains("\"startNodeId\":\"1\""));
    }

    @Test
    void professionalSubmissionRejectsInvalidRouteBeforeGoingOnline() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        stubLockedTopicRead(topicMapper);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service,"cmsTopicNodeMapper",mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "quotaService", mock(IQuotaService.class));
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", mock(CmsTopicNodeMapper.class));
        IAuditTaskService auditTaskService = mock(IAuditTaskService.class);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        com.chengyinhub.business.service.support.TopicBundleReviewService bundles =
                mock(com.chengyinhub.business.service.support.TopicBundleReviewService.class);
        ReflectionTestUtils.setField(service, "topicBundleReviewService", bundles);
        when(bundles.freezeForSubmission(10L, 100L)).thenReturn(
                new com.chengyinhub.business.service.support.TopicBundleReviewService.Submission(
                        "{\"schemaVersion\":2}", java.util.Collections.emptyList()));
        when(auditTaskService.submitOrRefreshTopicBundle(eq(10L), eq(100L), any(String.class))).thenReturn(99L);
        com.chengyinhub.business.domain.AuditTask task = new com.chengyinhub.business.domain.AuditTask();
        task.setId(99L);
        task.setStatus(com.chengyinhub.business.domain.AuditTask.STATUS_PENDING);
        when(auditTaskService.selectAuditTaskById(99L)).thenReturn(task);
        // R18-DRAFT:发布时补建自办活动壳 —— 成功路径会走到这两个协作者,不在场就是 NPE 假红。
        ReflectionTestUtils.setField(service, "cmsActivityMapper",
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "omsTicketService",
                mock(com.chengyinhub.business.service.IOmsTicketService.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", mock(OmsTicketMapper.class));
        // release-0917 同步 master:rule-funds 欠款逾期闸在 publishDraft 提交口与建壳处,手工装配须补上;mock 默认无逾期。
        ReflectionTestUtils.setField(service, "settlementDebtLedger",
                mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        when(topicMapper.clearDraftIfDraft(10L)).thenReturn(1);
        when(topicMapper.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(100L); topic.setProductType(ProductType.ORIENTEERING);
        topic.setRouteMode("BRANCH_GRAPH"); topic.setPublishMode("pro");
        topic.setName("夜跑路线");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        assertThrows(IllegalArgumentException.class, () -> publication(service).publishDraft(10L));

        verify(topicMapper, never()).publishRouteSnapshot(
                any(Long.class), any(), any(java.util.Date.class));
    }

    @Test
    void approvedBranchGraphPublishesLatestNormalizedSnapshot() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setStatus(1); topic.setProductType(ProductType.ORIENTEERING);
        topic.setRouteMode("BRANCH_GRAPH");
        topic.setRouteGraphJson("{\"schemaVersion\":1,\"startNodeId\":\"1\",\"terminalNodeIds\":[\"2\"]," +
                "\"edges\":[{\"id\":\"go\",\"fromNodeId\":\"1\",\"toNodeId\":\"2\"," +
                "\"trigger\":{\"type\":\"CHOICE\",\"outcomeCode\":\"COMPLETED\"}}]}");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        when(topicMapper.publishRouteSnapshot(eq(10L), any(String.class), any(java.util.Date.class))).thenReturn(1);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(node(1L, null), node(2L, null)));

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        publication(service).publishApprovedRouteSnapshot(10L);

        org.mockito.ArgumentCaptor<String> snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(topicMapper).publishRouteSnapshot(eq(10L), snapshot.capture(), any(java.util.Date.class));
        assertEquals(true, snapshot.getValue().contains("\"startNodeId\":1"));
    }

    @Test
    void pendingTopicCannotAdvancePublishedSnapshot() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setStatus(0); topic.setRouteMode("BRANCH_GRAPH");
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publication(service).publishApprovedRouteSnapshot(10L));

        assertEquals("主题尚未成功通过审核，拒绝推进路线发布快照", error.getMessage());
        verify(topicMapper, never()).publishRouteSnapshot(
                any(Long.class), any(), any(java.util.Date.class));
    }

    @Test
    void approvedLinearTopicPublishesNullGraphAndAdvancesPublishedVersion() {
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service,"cmsTopicNodeMapper",mock(CmsTopicNodeMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        CmsTopic topic = new CmsTopic();
        topic.setId(10L); topic.setMemberId(100L); topic.setStatus(1); topic.setRouteMode("LINEAR");
        CmsTopicNodeMapper nodeMapper = (CmsTopicNodeMapper) ReflectionTestUtils.getField(service, "cmsTopicNodeMapper");
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(java.util.Collections.singletonList(node(1L, null)));
        when(topicMapper.selectCmsTopicById(10L)).thenReturn(topic);
        when(topicMapper.publishRouteSnapshot(eq(10L), any(String.class), any(java.util.Date.class))).thenReturn(1);

        com.chengyinhub.business.service.support.TopicPublicationTestFixtures.wire(service);
        publication(service).publishApprovedRouteSnapshot(10L);

        org.mockito.ArgumentCaptor<String> snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(topicMapper).publishRouteSnapshot(eq(10L), snapshot.capture(), any(java.util.Date.class));
        // The authored LINEAR graph remains absent; formal publication now persists complete frozen content.
        org.junit.jupiter.api.Assertions.assertNull(topic.getRouteGraphJson());
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.getValue().contains("\"runtimeSnapshotVersion\":1"));
        org.junit.jupiter.api.Assertions.assertTrue(snapshot.getValue().contains("\"routeMode\":\"LINEAR\""));
    }

    @SuppressWarnings("unchecked")
    @Test
    void declaredOutcomesComeOnlyFromTheNodeActualGameplayTrigger() {
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        CmsMemberTemplateMapper templateMapper = mock(CmsMemberTemplateMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);

        CmsTopicNode preferenceNode = node(1L, 101L);
        CmsTopicNode advancedNode = node(2L, 102L);
        CmsTopicNode ordinaryNode = node(3L, null);
        CmsTopicNode d20Node = node(4L, 104L);
        when(nodeMapper.selectCmsTopicNodeList(any(CmsTopicNode.class)))
                .thenReturn(Arrays.asList(preferenceNode, advancedNode, ordinaryNode, d20Node));

        CmsMemberTemplate preference = new CmsMemberTemplate();
        preference.setValidationMethod(ValidationMethod.PREFERENCE_GROUP);
        preference.setPreferenceJson("{\"results\":{\"HISTORY\":{},\"ART\":{}}}");
        when(templateMapper.selectCmsMemberTemplateById(101L)).thenReturn(preference);
        CmsMemberTemplate advanced = new CmsMemberTemplate();
        advanced.setValidationMethod(ValidationMethod.PHOTO);
        advanced.setAdvancedConfigJson("{\"branch\":{\"enabled\":true,\"steps\":[" +
                "{\"terminal\":true,\"outcomeCode\":\"PHOTO_END\"}]}}");
        when(templateMapper.selectCmsMemberTemplateById(102L)).thenReturn(advanced);
        CmsMemberTemplate d20 = new CmsMemberTemplate();
        d20.setAdvancedConfigJson("{\"diceRoll\":{\"enabled\":true,\"mode\":\"d20\"}}");
        when(templateMapper.selectCmsMemberTemplateById(104L)).thenReturn(d20);

        Map<Long, Set<String>> declared = ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicPublicationTestWiring.owner(com.chengyinhub.business.service.support.TopicGraphTestWiring.shared(service), "declaredRouteOutcomes"), "declaredRouteOutcomes", 10L);

        assertEquals(set("PREFERENCE_RESULT:HISTORY", "PREFERENCE_RESULT:ART"), declared.get(1L));
        assertEquals(set("ADVANCED_RESULT:PHOTO_END"), declared.get(2L));
        assertEquals(set("CHOICE:COMPLETED"), declared.get(3L));
        assertEquals(set("ADVANCED_RESULT:D20_SUCCESS", "ADVANCED_RESULT:D20_FAILURE"), declared.get(4L));
    }

    private static CmsTopicNode node(Long id, Long templateId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(id); node.setTopicId(10L); node.setTemplateId(templateId); node.setStatus(1L);
        return node;
    }

    private static Set<String> set(String... values) {
        return new java.util.LinkedHashSet<String>(Arrays.asList(values));
    }
}
