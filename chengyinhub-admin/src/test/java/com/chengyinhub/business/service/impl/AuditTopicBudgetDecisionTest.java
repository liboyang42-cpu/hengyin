package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real review, facade and budget algorithms; unit doubles do not prove database rollback. */
class AuditTopicBudgetDecisionTest {
    private static final long TASK=9L, TOPIC=77L, OWNER=42L, ACTOR=11L;

    @Test void alreadyPublishedNewDecisionChecksBudgetBeforeAnyPublicationEffect() {
        Fixture f = new Fixture(301);
        assertThrows(ServiceException.class, () -> f.audit.review(TASK, ACTOR, f.request()));
        verify(f.nodes).countContentByTopicForUpdate(TOPIC);
        f.noPublicationEffects();
    }

    @Test void quotaDeniedNewDecisionStillChecksBudgetBecauseShellPathRemains() {
        Fixture f = new Fixture(301);
        when(f.quota.lockAndCheckThemeOnline(OWNER, TOPIC)).thenReturn("quota full");
        assertThrows(ServiceException.class, () -> f.audit.review(TASK, ACTOR, f.request()));
        var order = inOrder(f.quota, f.topics, f.nodes);
        order.verify(f.quota).lockAndCheckThemeOnline(OWNER, TOPIC);
        order.verify(f.topics).lockTopicContentBudget(TOPIC);
        order.verify(f.nodes).countContentByTopicForUpdate(TOPIC);
        f.noPublicationEffects();
    }

    @Test void normalBoundaryKeepsAlreadyPublishedSnapshotAndOwnersOfflineChoice() {
        Fixture f = new Fixture(300); f.topic.setUserStatus(0);
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        verify(f.nodes).countContentByTopicForUpdate(TOPIC);
        var update = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topics).updateCmsTopic(update.capture());
        assertEquals(1, update.getValue().getStatus());
        assertEquals(0, update.getValue().getUserStatus());
        verify(f.facade, never()).publishApprovedRouteSnapshot(anyLong());
        verify(f.facade).syncCreativeSquarePostVisibility(TOPIC, false);
        verify(f.facade).ensureSelfHostActivityShell(TOPIC);
    }

    @Test void quotaDeniedBoundaryRetainsItsExistingOfflineOutcome() {
        Fixture f = new Fixture(300);
        when(f.quota.lockAndCheckThemeOnline(OWNER, TOPIC)).thenReturn("quota full");
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        verify(f.nodes).countContentByTopicForUpdate(TOPIC);
        var update = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topics).updateCmsTopic(update.capture());
        assertEquals(0, update.getValue().getUserStatus());
    }

    @Test void exactSuccessfulReceiptReplayDoesNotRecheckChangedBudgetOrRepeatEffects() {
        Fixture f = new Fixture(301); f.installReplay();
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        verifyNoInteractions(f.topics, f.chapters, f.nodes, f.quota, f.im, f.facade);
        verify(f.tasks, never()).updateAuditTaskReviewCas(anyLong(),anyInt(),anyInt(),anyLong(),anyInt(),any());
        verify(f.records, never()).insertAuditRecord(any());
        verify(f.events, never()).insertBusinessAuditEvent(any());
    }

    @Test void changedPayloadCannotReuseSuccessfulReceiptAndStillHasNoEffects() {
        Fixture f = new Fixture(301); f.installReplay();
        AuditReviewDTO changed = f.request(); changed.setReasonText("different");
        assertThrows(ServiceException.class, () -> f.audit.review(TASK, ACTOR, changed));
        verifyNoInteractions(f.topics, f.chapters, f.nodes, f.quota, f.im, f.facade);
        verify(f.records, never()).insertAuditRecord(any());
    }

    @Test void reportNotUpheldRemainsAContentNoOpWithoutBudgetRejection() {
        Fixture f = new Fixture(301); f.task.setSourceType("report");
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        verify(f.facade, never()).topicSupplyAutoOnlineBlockReason(anyLong());
        verifyNoInteractions(f.chapters, f.nodes, f.quota);
        f.noPublicationEffects();
    }

    @Test void rejectingOverBudgetContentCanStillTakeItOffline() {
        Fixture f = new Fixture(301); AuditReviewDTO reject=f.request(); reject.setStatus(AuditTask.STATUS_REJECTED);
        assertEquals(1, f.audit.review(TASK, ACTOR, reject));
        verify(f.facade, never()).topicSupplyAutoOnlineBlockReason(anyLong());
        verifyNoInteractions(f.chapters, f.nodes, f.quota);
        var update=org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(f.topics).updateCmsTopic(update.capture());
        assertEquals(2, update.getValue().getStatus());
        assertEquals(0, update.getValue().getUserStatus());
    }

    @Test void currentLockedRowKeepsAnAuthorsCommittedOfflineChoiceDespiteStaleRrOnlineRow() {
        Fixture f = new Fixture(300);
        f.task.setSourceType("post_review_flag");
        CmsTopic current = f.currentRow(1, 0, 1, 1L);
        when(f.topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(current);
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        var order = inOrder(f.quota, f.topics);
        order.verify(f.quota).lockAndCheckThemeOnline(OWNER, TOPIC);
        // The decision and its real supply preflight each read the same current row after quota locking.
        order.verify(f.topics, times(2)).selectCmsTopicByIdForUpdate(TOPIC);
        f.assertPublicationChoice(0);
    }

    @Test void staleSafetyHoldCannotRelistAfterTheCurrentHoldWasCleared() {
        Fixture f = new Fixture(300); f.topic.setCheckStatus(2);
        when(f.topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(f.currentRow(1, 0, 1, 1L));
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        f.assertPublicationChoice(0);
    }

    @Test void currentSafetyHoldStillUsesTheExistingManualReleasePolicy() {
        Fixture f = new Fixture(300);
        when(f.topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(f.currentRow(1, 0, 2, 1L));
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        f.assertPublicationChoice(1);
    }

    @Test void currentPublishedVersionPreventsAStaleSnapshotFromPublishingAgain() {
        Fixture f = new Fixture(300); f.topic.setPublishedConfigVersion(0L);
        when(f.topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(f.currentRow(1, 0, 1, 7L));
        assertEquals(1, f.audit.review(TASK, ACTOR, f.request()));
        f.assertPublicationChoice(0);
    }

    @Test void changedOwnerCannotContinueUnderTheWrongMemberLock() {
        Fixture f = new Fixture(300); CmsTopic current=f.currentRow(1, 0, 1, 1L);
        current.setMemberId(OWNER + 1);
        when(f.topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(current);
        assertThrows(ServiceException.class, () -> f.audit.review(TASK, ACTOR, f.request()));
        f.noPublicationEffects();
        verify(f.facade, never()).topicSupplyAutoOnlineBlockReason(anyLong());
    }

    private static final class Fixture {
        final AuditTaskServiceImpl audit=new AuditTaskServiceImpl();
        final AuditTaskMapper tasks=mock(AuditTaskMapper.class);
        final AuditRecordMapper records=mock(AuditRecordMapper.class);
        final BusinessAuditEventMapper events=mock(BusinessAuditEventMapper.class);
        final CmsTopicMapper topics=mock(CmsTopicMapper.class);
        final CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);
        final IQuotaService quota=mock(IQuotaService.class);
        final IImService im=mock(IImService.class);
        final CmsTopicServiceImpl facade=spy(new CmsTopicServiceImpl());
        final CmsTopic topic=new CmsTopic();
        final AuditTask task=new AuditTask();
        Fixture(long count) {
            topic.setId(TOPIC); topic.setMemberId(OWNER); topic.setStatus(1); topic.setUserStatus(1);
            topic.setProductType(ProductType.ORIENTEERING); topic.setPublishedConfigVersion(1L);
            task.setId(TASK); task.setBizType(AuditTask.BIZ_TOPIC); task.setBizId(TOPIC);
            task.setStatus(AuditTask.STATUS_PENDING); task.setVersion(0); task.setSubmitterId(OWNER);
            task.setSourceType("user_publish");
            when(tasks.selectAuditTaskById(TASK)).thenReturn(task);
            when(tasks.updateAuditTaskReviewCas(anyLong(),anyInt(),anyInt(),anyLong(),anyInt(),any())).thenReturn(1);
            when(records.insertAuditRecord(any())).thenReturn(1);
            when(events.insertBusinessAuditEvent(any())).thenReturn(1);
            when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
            when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            when(topics.lockTopicContentBudget(TOPIC)).thenReturn(TOPIC);
            when(topics.updateCmsTopic(any())).thenReturn(1);
            when(chapters.countContentByTopicForUpdate(TOPIC)).thenReturn(30L);
            when(nodes.countContentByTopicForUpdate(TOPIC)).thenReturn(count);
            TopicContentWriteGuard budget=new TopicContentWriteGuard(topics,chapters,null,null,null);
            ReflectionTestUtils.setField(budget,"cmsTopicNodeMapper",nodes);
            TopicPublicationContentGuard publication=new TopicPublicationContentGuard();
            ReflectionTestUtils.setField(publication,"contentWriteGuard",budget);
            ReflectionTestUtils.setField(facade,"cmsTopicMapper",topics);
            ReflectionTestUtils.setField(facade,"publicationContentGuard",publication);
            doNothing().when(facade).syncCreativeSquarePostVisibility(anyLong(),anyBoolean());
            doNothing().when(facade).ensureSelfHostActivityShell(anyLong());
            ObjectProvider<ICmsTopicService> provider=mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(facade);
            ObjectProvider<IImService> imProvider=mock(ObjectProvider.class);
            when(imProvider.getObject()).thenReturn(im);
            ReflectionTestUtils.setField(audit,"auditTaskMapper",tasks);
            ReflectionTestUtils.setField(audit,"auditRecordMapper",records);
            ReflectionTestUtils.setField(audit,"businessAuditEventMapper",events);
            ReflectionTestUtils.setField(audit,"cmsTopicMapper",topics);
            ReflectionTestUtils.setField(audit,"quotaService",quota);
            ReflectionTestUtils.setField(audit,"cmsTopicServiceProvider",provider);
            ReflectionTestUtils.setField(audit,"imServiceProvider",imProvider);
            ReflectionTestUtils.setField(audit,"mediaPublicationGuard",mock(AppMediaPublicationGuard.class));
        }
        CmsTopic currentRow(int status, int userStatus, int checkStatus, long version) {
            CmsTopic current = new CmsTopic(); current.setId(TOPIC); current.setMemberId(OWNER);
            current.setProductType(ProductType.ORIENTEERING); current.setStatus(status);
            current.setUserStatus(userStatus); current.setCheckStatus(checkStatus);
            current.setPublishedConfigVersion(version); return current;
        }
        void assertPublicationChoice(int userStatus) {
            var update = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
            verify(topics).updateCmsTopic(update.capture());
            assertEquals(1, update.getValue().getStatus());
            assertEquals(userStatus, update.getValue().getUserStatus());
            assertNull(update.getValue().getBetaFlag(), "published content must not restart beta");
            verify(facade, never()).publishApprovedRouteSnapshot(anyLong());
            verify(facade).syncCreativeSquarePostVisibility(TOPIC, userStatus == 1);
        }
        AuditReviewDTO request() {
            AuditReviewDTO request=new AuditReviewDTO(); request.setStatus(AuditTask.STATUS_APPROVED);
            request.setReasonText("approved"); request.setRequestId("budget-review"); return request;
        }
        void installReplay() {
            task.setStatus(AuditTask.STATUS_APPROVED); task.setVersion(1);
            BusinessAuditEvent event=new BusinessAuditEvent(); event.setActorMemberId(ACTOR); event.setReason("approved");
            event.setBeforeJson("{\"status\":0,\"version\":0}"); event.setAfterJson("{\"status\":1,\"version\":1}");
            when(events.selectByDomainActionRequestForUpdate("PLATFORM",TASK,"AUDIT_DECISION_APPROVE","budget-review"))
                    .thenReturn(event);
        }
        void noPublicationEffects() {
            verify(topics,never()).updateCmsTopic(any());
            verify(topics,never()).publishRouteSnapshot(any(),any(),any());
            verify(facade,never()).publishApprovedRouteSnapshot(anyLong());
            verify(facade,never()).syncCreativeSquarePostVisibility(anyLong(),anyBoolean());
            verify(facade,never()).ensureSelfHostActivityShell(anyLong());
        }
    }
}
