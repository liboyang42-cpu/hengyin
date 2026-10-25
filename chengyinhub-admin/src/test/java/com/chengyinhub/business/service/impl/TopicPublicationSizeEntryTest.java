package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IQuotaService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.support.AppMediaPublicationGuard;
import com.chengyinhub.business.service.support.TopicBundleReviewService;
import com.chengyinhub.business.service.support.TopicContentWriteGuard;
import com.chengyinhub.business.service.support.TopicPublicationContentGuard;
import com.chengyinhub.business.service.support.TopicSelfHostActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Entry coverage only: these doubles do not establish MySQL isolation or concurrent writer fencing. */
class TopicPublicationSizeEntryTest {
    private static final long TOPIC = 7L;
    private static final long OWNER = 9L;

    @Test
    void nonDraftRepublishRejectsThirtyOneChaptersBeforeQuotaSnapshotOrShell() {
        Fixture f = new Fixture(31, 10);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service.publishBeforeReview(TOPIC, OWNER));
        assertEquals("发布前请先调整:主题最多 30 章,当前 31 章,请合并或删减章节", error.getMessage());
        f.assertNoPublicationEffects();
    }

    @Test
    void nonDraftRepublishRejectsThreeHundredAndOneNodesBeforeQuotaSnapshotOrShell() {
        Fixture f = new Fixture(30, 301);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service.publishBeforeReview(TOPIC, OWNER));
        assertEquals("发布前请先调整:主题最多 300 个节点,当前 301 个,请删减节点", error.getMessage());
        f.assertNoPublicationEffects();
    }

    @Test
    void bundleSubmissionRejectsOverBudgetBeforePersistingAnAuditTask() {
        Fixture f = new Fixture(30, 301);
        assertThrows(IllegalStateException.class, () -> f.service.submitTopicBundle(TOPIC, OWNER));
        verify(f.bundle).freezeForSubmission(TOPIC, OWNER);
        f.assertNoPublicationEffects();
    }

    @Test
    void approvedFirstPublicationRejectsOverBudgetBeforeAdvancingSnapshot() {
        Fixture f = new Fixture(31, 300);
        assertThrows(IllegalStateException.class, () -> f.service.publishApprovedRouteSnapshot(TOPIC));
        verify(f.topics, atLeastOnce()).selectCmsTopicByIdForUpdate(TOPIC);
        f.assertNoPublicationEffects();
    }

    @Test
    void unapprovedTopicRetainsTheOriginalStatusRejectionBeforeCountingRows() {
        Fixture f = new Fixture(31, 301);
        f.topic.setStatus(0);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> f.service.publishApprovedRouteSnapshot(TOPIC));
        assertEquals("主题尚未成功通过审核，拒绝推进路线发布快照", error.getMessage());
        verifyNoInteractions(f.chapters, f.nodes);
        f.assertNoPublicationEffects();
    }

    @Test
    void approvedFirstPublicationAtTheBoundaryKeepsTheOriginalSnapshotWrite() {
        Fixture f = new Fixture(30, 300);
        f.service.publishApprovedRouteSnapshot(TOPIC);
        verify(f.topics).publishRouteSnapshot(eq(TOPIC), eq("frozen-route"), any());
        verifyNoInteractions(f.quota, f.audit, f.square, f.activity);
    }

    @Test
    void nonDraftRepublishAtTheBoundaryStillPublishesAndBuildsTheShellOnce() {
        Fixture f = new Fixture(30, 300);
        assertEquals(true, f.service.publishBeforeReview(TOPIC, OWNER));
        verify(f.quota).lockAndCheckThemeOnline(OWNER, TOPIC);
        verify(f.topics).publishRouteSnapshot(eq(TOPIC), eq("frozen-route"), any());
        verify(f.topics).updateCmsTopic(any());
        verify(f.square).syncTopicPostVisibility(TOPIC, true);
        verify(f.activity).ensureSelfHostActivityShell(TOPIC);
    }

    @Test
    void bundleSubmissionAtTheBoundaryRetainsItsAuditAndPublicationResult() {
        Fixture f = new Fixture(30, 300);
        var result = f.service.submitTopicBundle(TOPIC, OWNER);
        assertEquals(true, result.isPublished());
        assertEquals("PENDING", result.getReviewState());
        verify(f.audit).submitOrRefreshTopicBundle(TOPIC, OWNER, "bundle-snapshot");
        verify(f.topics).publishRouteSnapshot(eq(TOPIC), eq("frozen-route"), any());
        verify(f.activity).ensureSelfHostActivityShell(TOPIC);
    }

    @Test
    void bundlePreparesOriginalDeclarationProducersBeforeHashAndChecksThemAfterPublication() {
        Fixture f = new Fixture(30, 300);
        f.service.submitTopicBundle(TOPIC, OWNER);
        TopicPublicationContentGuard guard = (TopicPublicationContentGuard)
                ReflectionTestUtils.getField(f.service, "publicationContentGuard");
        var sequence = inOrder(guard, f.bundle, f.audit);
        sequence.verify(guard).deriveOptionTagsForPublication(f.topic);
        sequence.verify(guard).assertStateKeysRegistered(eq(f.topic), any());
        sequence.verify(f.bundle).freezeForSubmission(TOPIC, OWNER);
        sequence.verify(f.audit).submitOrRefreshTopicBundle(TOPIC, OWNER, "bundle-snapshot");
        sequence.verify(f.bundle).assertDeclarationsUnchanged(TOPIC, "bundle-snapshot");
    }

    @Test
    void createdOrdinaryContentUsesTheTypedPathWithoutAnAttributeFeatureGate() {
        Fixture f = new Fixture(1, 1);
        assertEquals(1, f.service.persistCreatedRouteSnapshot(TOPIC));
        TopicPublicationContentGuard guard = (TopicPublicationContentGuard)
                ReflectionTestUtils.getField(f.service, "publicationContentGuard");
        verify(guard, never()).requiresFullRuntimeSnapshot(any());
        verify(guard).normalizedBranchRouteForPublish(eq(f.topic), any());
        verify(f.topics).publishRouteSnapshot(eq(TOPIC), eq("frozen-route"), any());
        verifyNoInteractions(f.quota, f.audit, f.square, f.activity);
    }

    @Test
    void approvalConsumesCurrentDeclarationsWithoutDerivingUnreviewedRows() {
        Fixture f = new Fixture(1, 1);
        f.service.publishApprovedRouteSnapshot(TOPIC);
        var runtime = (com.chengyinhub.business.service.ITopicRouteRuntimeService)
                ReflectionTestUtils.getField(f.service, "topicRouteRuntimeService");
        TopicPublicationContentGuard guard = (TopicPublicationContentGuard)
                ReflectionTestUtils.getField(f.service, "publicationContentGuard");
        verify(runtime, never()).deriveTemplateCluesForPublication(any());
        verify(guard, never()).deriveOptionTagsForPublication(any());
        verify(f.topics).publishRouteSnapshot(eq(TOPIC), eq("frozen-route"), any());
    }

    private static final class Fixture {
        final TopicPublicationService service = new TopicPublicationService();
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final IQuotaService quota = mock(IQuotaService.class);
        final IAuditTaskService audit = mock(IAuditTaskService.class);
        final IUmsCreativeSquareService square = mock(IUmsCreativeSquareService.class);
        final TopicSelfHostActivityService activity = mock(TopicSelfHostActivityService.class);
        final TopicBundleReviewService bundle = mock(TopicBundleReviewService.class);
        final CmsTopic topic = new CmsTopic();

        Fixture(int chapterCount, int nodeCount) {
            topic.setId(TOPIC);
            topic.setMemberId(OWNER);
            topic.setStatus(1);
            topic.setUserStatus(1);
            when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
            when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            when(topics.lockTopicContentBudget(TOPIC)).thenReturn(TOPIC);
            when(topics.publishRouteSnapshot(eq(TOPIC), any(), any())).thenReturn(1);
            when(topics.updateCmsTopic(any())).thenReturn(1);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(
                    Collections.nCopies(chapterCount, new CmsTopicChapter()));
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(
                    Collections.nCopies(nodeCount, new CmsTopicNode()));
            when(chapters.countContentByTopicForUpdate(TOPIC)).thenReturn((long) chapterCount);
            when(nodes.countContentByTopicForUpdate(TOPIC)).thenReturn((long) nodeCount);
            when(bundle.freezeForSubmission(TOPIC, OWNER)).thenReturn(
                    new TopicBundleReviewService.Submission("bundle-snapshot", Collections.emptyList()));
            when(bundle.freezeForSubmission(eq(TOPIC), eq(OWNER), any()))
                    .thenAnswer(call -> bundle.freezeForSubmission(TOPIC, OWNER));
            when(audit.submitOrRefreshTopicBundle(TOPIC, OWNER, "bundle-snapshot")).thenReturn(12L);
            AuditTask task = new AuditTask();
            task.setStatus(AuditTask.STATUS_PENDING);
            when(audit.selectAuditTaskById(12L)).thenReturn(task);

            // Exercise the real size algorithm; unrelated content validators have their existing suites.
            TopicPublicationContentGuard guard = spy(new TopicPublicationContentGuard());
            TopicContentWriteGuard writeGuard = new TopicContentWriteGuard(topics, chapters, null, null, null);
            ReflectionTestUtils.setField(writeGuard, "cmsTopicNodeMapper", nodes);
            ReflectionTestUtils.setField(guard, "contentWriteGuard", writeGuard);
            ReflectionTestUtils.setField(guard, "cmsTopicChapterMapper", chapters);
            ReflectionTestUtils.setField(guard, "cmsTopicNodeMapper", nodes);
            doNothing().when(guard).assertStateKeysRegistered(any());
            doNothing().when(guard).deriveOptionTagsForPublication(any());
            doNothing().when(guard).assertStateKeysRegistered(any(), any());
            doNothing().when(guard).assertThoughtRefsResolvable(any());
            doNothing().when(guard).assertJourneyStoryReferences(any());
            doNothing().when(guard).assertThemeRuntimeReferences(any());
            doNothing().when(guard).assertThemeRuntimeReferences(any(), any());
            doNothing().when(guard).assertBlockNarrativeNpcReferences(any());
            doNothing().when(guard).assertBlockSysVarReferences(any());
            doReturn("frozen-route").when(guard).normalizedBranchRouteForPublish(any());
            doReturn("frozen-route").when(guard).normalizedBranchRouteForPublish(any(), any());
            var runtime = mock(com.chengyinhub.business.service.ITopicRouteRuntimeService.class);
            var keyService = mock(com.chengyinhub.business.service.support.ThemeStateKeyService.class);
            when(keyService.listCurrentDeclarations(TOPIC)).thenReturn(Collections.emptyList());
            ReflectionTestUtils.setField(service, "topicRouteRuntimeService", runtime);
            ReflectionTestUtils.setField(service, "themeStateKeyService", keyService);
            ReflectionTestUtils.setField(service, "publicationContentGuard", guard);
            ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
            ReflectionTestUtils.setField(service, "quotaService", quota);
            ReflectionTestUtils.setField(service, "auditTaskService", audit);
            ReflectionTestUtils.setField(service, "topicBundleReviewService", bundle);
            ReflectionTestUtils.setField(service, "umsCreativeSquareService", square);
            ReflectionTestUtils.setField(service, "selfHostActivityService", activity);
            ReflectionTestUtils.setField(service, "mediaPublicationGuard", mock(AppMediaPublicationGuard.class));
        }

        void assertNoPublicationEffects() {
            verify(topics, never()).publishRouteSnapshot(any(), any(), any());
            verify(topics, never()).updateCmsTopic(any());
            verifyNoInteractions(quota, audit, square, activity);
        }
    }
}
