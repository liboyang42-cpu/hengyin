package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.business.mapper.AuditRecordMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.service.impl.AuditTaskServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.chengyinhub.business.service.support.LegacyCmsUnitGuardFixture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一审核结论必须同步到模板真源与模板库投影。 */
class AuditTemplateDecisionProjectionTest {
    private LegacyCmsUnitGuardFixture provenance;
    @AfterEach void closeLegacySource() { if (provenance != null) provenance.close(); }
    private AuditTaskServiceImpl service;
    private AuditTaskMapper taskMapper;
    private ICmsMemberTemplateService memberService;
    private ICmsTemplateLibraryService libraryService;
    private com.chengyinhub.business.mapper.CmsMemberTemplateMapper templateMapper;
    private com.chengyinhub.business.mapper.CmsTemplateLibraryMapper libraryMapper;
    private CmsMemberTemplate canonical;
    private CmsTemplateLibrary projection;
    private AuditTask task;

    @BeforeEach
    void setUp() throws Exception {
        provenance = new LegacyCmsUnitGuardFixture(91L);
        taskMapper = mock(AuditTaskMapper.class);
        memberService = mock(ICmsMemberTemplateService.class);
        libraryService = mock(ICmsTemplateLibraryService.class);
        AuditRecordMapper recordMapper = mock(AuditRecordMapper.class);
        com.chengyinhub.business.mapper.BusinessAuditEventMapper auditMapper =
                mock(com.chengyinhub.business.mapper.BusinessAuditEventMapper.class);
        service = new AuditTaskServiceImpl();
        ReflectionTestUtils.setField(service, "legacyTemplateApprovalBoundary", provenance.approvalBoundary);
        ReflectionTestUtils.setField(service, "auditTaskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "auditRecordMapper", recordMapper);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", memberService);
        ReflectionTestUtils.setField(service, "cmsTemplateLibraryService", libraryService);
        task = new AuditTask();
        task.setId(3L);
        task.setBizType(AuditTask.BIZ_TEMPLATE);
        task.setBizId(91L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(3L)).thenReturn(task);
        when(taskMapper.updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any())).thenReturn(1);
        when(recordMapper.insertAuditRecord(any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(memberService.updateCmsMemberTemplate(any())).thenReturn(1);
        CmsTemplateLibrary library = new CmsTemplateLibrary();
        library.setId(191L);
        when(libraryService.selectCmsTemplateLibraryList(any())).thenReturn(Collections.singletonList(library));
        when(libraryService.updateCmsTemplateLibrary(any())).thenReturn(1);
        templateMapper = mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class);
        libraryMapper = mock(com.chengyinhub.business.mapper.CmsTemplateLibraryMapper.class);
        ReflectionTestUtils.setField(service, "templateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "libraryMapper", libraryMapper);
        org.springframework.beans.factory.ObjectProvider<IImService> im = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(im.getObject()).thenReturn(mock(IImService.class));
        ReflectionTestUtils.setField(service, "imServiceProvider", im);
        canonical = new CmsMemberTemplate();
        canonical.setId(91L); canonical.setMemberId(7L); canonical.setDelFlag(0);
        canonical.setDraftStatus(1); canonical.setStatus(0L); canonical.setCheckStatus(0);
        canonical.setQuestionAnswer("旧答案");
        projection = new CmsTemplateLibrary();
        projection.setId(191L); projection.setMemberTemplateId(91L); projection.setMemberId(7L);
        projection.setDelFlag(0); projection.setStatus(0L); projection.setPublishStatus(0L);
        projection.setQuestionAnswer("旧答案");
        task.setSubmitterId(7L);
        task.setSnapshotJson(com.chengyinhub.business.service.support.TemplateAuditSnapshot.personal(canonical));
        when(taskMapper.selectAuditTaskByIdForUpdate(3L)).thenAnswer(inv -> task);
        when(templateMapper.selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(91L)))
                .thenReturn(Collections.singletonList(canonical));
        when(libraryMapper.selectByMemberTemplateForUpdate(91L)).thenReturn(Collections.singletonList(projection));
        when(libraryMapper.selectCmsTemplateLibraryById(191L)).thenReturn(projection);
        ReflectionTestUtils.setField(service, "mediaPublicationGuard", com.chengyinhub.business.service.support.MediaPublicationTestFixtures.template(7L));
        when(templateMapper.selectCmsMemberTemplateById(91L)).thenReturn(canonical);
        when(templateMapper.applyAuditDecision(any(), anyLong())).thenReturn(1);
        when(libraryMapper.applyAuditDecision(any(), anyLong())).thenReturn(1);
    }

    @Test
    void personalApprovalPublishesOnlyCanonicalTemplate() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        service.review(3L, 8L, decision);

        CmsMemberTemplate member = captureMember();
        assertEquals(1L, member.getStatus());
        assertEquals(1, member.getCheckStatus());
        assertEquals(1L, member.getPublishStatus());
        assertEquals("", member.getReason());
        verify(libraryService, never()).updateCmsTemplateLibrary(any());
    }

    @Test
    void personalRejectionKeepsTemplateOfflineWithoutTouchingMarketProjection() {
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("内容不完整");
        service.review(3L, 8L, decision);

        CmsMemberTemplate member = captureMember();
        assertEquals(2L, member.getStatus());
        assertEquals(2, member.getCheckStatus());
        assertEquals(0L, member.getPublishStatus());
        assertEquals("内容不完整", member.getReason());
        verify(libraryService, never()).updateCmsTemplateLibrary(any());
    }

    @Test
    void marketRejectionOnlyRejectsProjectionAndKeepsPersonalTemplateUsable() {
        stubMarketTask();
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_REJECTED);
        decision.setReasonText("广场展示信息不完整");

        service.review(3L, 8L, decision);

        verify(memberService, never()).updateCmsMemberTemplate(any());
        CmsTemplateLibrary library = captureLibrary();
        assertEquals(2L, library.getStatus());
        assertEquals(0L, library.getPublishStatus());
        assertEquals("广场展示信息不完整", library.getReason());
    }

    @Test
    void marketProjectionFailureAbortsReviewTransaction() {
        stubMarketTask();
        when(libraryMapper.applyAuditDecision(any(), anyLong())).thenReturn(0);
        AuditReviewDTO decision = new AuditReviewDTO();
        decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);

        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
    }

    @Test
    void staleReviewPageCannotApproveRefreshedSnapshot() {
        stubMarketTask();
        task.setVersion(1);
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        verify(taskMapper, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
    }

    @Test
    void changedAnswerAndDeletedProjectionRejectOldApproval() {
        stubMarketTask();
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        projection.setQuestionAnswer("改后的答案");
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        projection.setQuestionAnswer("旧答案"); projection.setDelFlag(2);
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
    }

    @Test
    void personalDraftAndForeignOwnerCannotBeApproved() {
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        canonical.setDraftStatus(0);
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        canonical.setDraftStatus(1); canonical.setMemberId(99L);
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        verify(templateMapper, never()).applyAuditDecision(any(), anyLong());
    }

    @Test
    void reviewLocksCanonicalThenProjectionBeforeTask() {
        stubMarketTask();
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0);
        decision.setStatus(AuditTask.STATUS_APPROVED);
        service.review(3L, 8L, decision);
        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(templateMapper, libraryMapper, taskMapper);
        locks.verify(templateMapper).selectCmsMemberTemplateByIdsForUpdate(Collections.singletonList(91L));
        locks.verify(libraryMapper).selectByMemberTemplateForUpdate(91L);
        locks.verify(taskMapper).selectAuditTaskByIdForUpdate(3L);
        locks.verify(taskMapper).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
    }

    private void stubMarketTask() {
        task = new AuditTask();
        task.setId(3L);
        canonical.setStatus(1L); canonical.setCheckStatus(1);
        task.setSubmitterId(7L);
        task.setSnapshotJson(com.chengyinhub.business.service.support.TemplateAuditSnapshot.market(projection));
        task.setBizType(AuditTask.BIZ_TEMPLATE_LIBRARY);
        task.setBizId(191L);
        task.setStatus(AuditTask.STATUS_PENDING);
        task.setVersion(0);
        when(taskMapper.selectAuditTaskById(3L)).thenReturn(task);
    }

    private CmsMemberTemplate captureMember() {
        ArgumentCaptor<CmsMemberTemplate> captor = ArgumentCaptor.forClass(CmsMemberTemplate.class);
        verify(templateMapper).applyAuditDecision(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        return captor.getValue();
    }

    private CmsTemplateLibrary captureLibrary() {
        ArgumentCaptor<CmsTemplateLibrary> captor = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(libraryMapper).applyAuditDecision(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        return captor.getValue();
    }

    @Test
    void missingApprovalGuardRejectsBeforeTaskDecisionOrProjectionWrite() {
        ReflectionTestUtils.setField(service, "legacyTemplateApprovalBoundary", null);
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0); decision.setStatus(AuditTask.STATUS_APPROVED);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.review(3L, 8L, decision));
        assertEquals("LEGACY_TEMPLATE_APPROVAL_GUARD_UNAVAILABLE", error.getMessage());
        verify(taskMapper, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(templateMapper, never()).applyAuditDecision(any(), anyLong());
        verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
    }

    @Test
    void markerNullButSidecarPresentCannotPassGenericApproval() {
        provenance.addSidecar(91L);
        AuditReviewDTO decision = new AuditReviewDTO(); decision.setExpectedVersion(0); decision.setStatus(AuditTask.STATUS_APPROVED);
        assertThrows(ServiceException.class, () -> service.review(3L, 8L, decision));
        verify(taskMapper, never()).updateAuditTaskReviewCas(anyLong(), anyInt(), anyInt(), anyLong(), anyInt(), any());
        verify(templateMapper, never()).applyAuditDecision(any(), anyLong());
        verify(libraryMapper, never()).applyAuditDecision(any(), anyLong());
    }
}
