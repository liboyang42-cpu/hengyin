package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ActivityCancelManualRefundCaseVO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartialChapterAftercareTest {
    private final CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
    private final RegistrationEntitlementMapper entitlements = mock(RegistrationEntitlementMapper.class);
    private final IActivityCancelManualRefundCaseService cases = mock(IActivityCancelManualRefundCaseService.class);
    private final IRefundService refunds = mock(IRefundService.class);
    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsActivityMapper activities = mock(CmsActivityMapper.class);

    private CmsRegistration registration() {
        CmsRegistration row = new CmsRegistration();
        row.setId(78L); row.setMemberId(9008L); row.setOwnerType(2L); row.setOwnerId(24L);
        row.setParentOrderNo("P2026092203105A0B"); row.setRegistrationNo("R78");
        row.setPurchaseKind(3); row.setVerificationStatus(1);
        row.setPaymentStatus(2); row.setRegistrationStatus(2);
        row.setPayableAmount(new BigDecimal("69.00")); row.setWechatPaymentAmount(new BigDecimal("69.00"));
        return row;
    }
    private TeamGroupServiceImpl service() {
        CmsRegistration row = registration();
        when(registrations.selectCmsRegistrationById(78L)).thenReturn(row);
        when(registrations.selectCmsRegistrationByIdForUpdate(78L)).thenReturn(row);
        when(registrations.selectByParentOrderNoForUpdate(row.getParentOrderNo(),24L))
            .thenReturn(Collections.singletonList(row));
        CmsActivity activity = new CmsActivity();activity.setId(24L);activity.setTopicId(990028L);activity.setMemberId(9003L);
        CmsTopic topic = new CmsTopic();topic.setId(990028L);topic.setMemberId(9003L);
        when(activities.selectCmsActivityById(24L)).thenReturn(activity);
        when(topics.selectCmsTopicById(990028L)).thenReturn(topic);
        List<RegistrationEntitlement> chapters = new ArrayList<>();
        for (long id=124; id<=127; id++) {
            RegistrationEntitlement e = new RegistrationEntitlement(); e.setRegistrationId(78L);
            e.setChapterId(id);e.setTopicId(990028L);e.setChapterName("Chapter "+id);e.setStatus(id==124 ? 0 : 1);
            chapters.add(e);
        }
        when(entitlements.selectByRegistrationForUpdate(78L)).thenReturn(chapters);
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service,"cmsRegistrationMapper",registrations);
        ReflectionTestUtils.setField(service,"cmsActivityMapper",activities);
        ReflectionTestUtils.setField(service,"cmsTopicMapper",topics);
        ReflectionTestUtils.setField(service,"entitlementMapper",entitlements);
        ReflectionTestUtils.setField(service,"manualRefundCaseService",cases);
        ReflectionTestUtils.setField(service,"refundService",refunds);
        return service;
    }
    @Test void playerAndOrganizerCreateSameScopeWithoutPayoutOrProgressChanges() {
        TeamGroupServiceImpl service=service();
        final ActivityCancelManualRefundCase[] saved={null};
        when(cases.findCase(24L,"P2026092203105A0B")).thenAnswer(a -> {
            if(saved[0]==null)return null;
            ActivityCancelManualRefundCaseVO vo=new ActivityCancelManualRefundCaseVO();
            vo.setId(91L);vo.setVerifiedRegistrationIds(Collections.singletonList(78L));
            vo.setUnverifiedRegistrationIds(Collections.emptyList());
            return vo;
        });
        when(cases.recordCase(any())).thenAnswer(a -> {saved[0]=a.getArgument(0);return new ActivityCancelManualRefundCaseVO();});
        assertEquals(ITeamGroupService.CANCEL_MANUAL_REVIEW,service.cancelPaidRegistration(78L,9008L));
        assertEquals(ITeamGroupService.CANCEL_MANUAL_REVIEW,service.cancelPaidRegistrationByOwner(78L,9003L));
        verify(cases,times(1)).recordCase(any());
        assertEquals("PARTIAL_CHAPTERS",saved[0].getCaseType());
        assertEquals(6900L,saved[0].getPaidCashAmountFen());
        assertEquals("[78]",saved[0].getVerifiedRegistrationIdsJson());
        assertEquals("[]",saved[0].getUnverifiedRegistrationIdsJson());
        assertTrue(saved[0].getChapterSnapshotJson().contains("\"chapterId\":124"));
        assertNull(saved[0].getAssessedRefundAmountFen());
        verifyNoInteractions(refunds);
        verify(entitlements,never()).invalidateByRegistration(anyLong(),anyString());
        assertThrows(RuntimeException.class,()->service.cancelPaidRegistration(78L,9004L));
    }
    @Test void failedCaseInsertCannotReturnAcceptedOrMoveMoney() {
        TeamGroupServiceImpl service=service();
        when(cases.recordCase(any())).thenThrow(new ServiceException("insert failed"));
        assertThrows(ServiceException.class,()->service.cancelPaidRegistration(78L,9008L));
        verifyNoInteractions(refunds);
    }
    @Test void caseValidationRejectsDuplicateOrWholeConsumedChapters() {
        ActivityCancelManualRefundCaseServiceImpl service=new ActivityCancelManualRefundCaseServiceImpl();
        ActivityCancelManualRefundCaseMapper mapper=mock(ActivityCancelManualRefundCaseMapper.class);
        ReflectionTestUtils.setField(service,"mapper",mapper);
        ActivityCancelManualRefundCase row=new ActivityCancelManualRefundCase();
        row.setActivityId(24L);row.setOwnerMemberId(9003L);row.setActorMemberId(9008L);
        row.setParentOrderNo("P78");row.setPayerRegistrationId(78L);row.setPayerMemberId(9008L);
        row.setPaidCashAmountFen(6900L);row.setVerifiedRegistrationIdsJson("[78]");
        row.setUnverifiedRegistrationIdsJson("[]");row.setCaseType("PARTIAL_CHAPTERS");
        row.setStatus(ActivityCancelManualRefundCase.STATUS_PENDING_ASSESSMENT);
        String good="[{\"registrationId\":78,\"chapterId\":124,\"status\":0},{\"registrationId\":78,\"chapterId\":125,\"status\":1}]";
        row.setChapterSnapshotJson(good.replace("\"chapterId\":125","\"chapterId\":124"));
        assertThrows(ServiceException.class,()->service.recordCase(row));
        row.setChapterSnapshotJson(good.replace("\"status\":0","\"status\":1"));
        assertThrows(ServiceException.class,()->service.recordCase(row));
        row.setChapterSnapshotJson(good);row.setId(91L);row.setVersion(0L);
        when(mapper.insertCase(any())).thenReturn(1);
        when(mapper.selectByActivityAndParent(24L,"P78")).thenReturn(row);
        ActivityCancelManualRefundCaseVO vo=service.recordCase(row);
        assertEquals("PARTIAL_CHAPTERS",vo.getCaseType());
        assertEquals(good,vo.getChapterSnapshotJson());
        row.setCaseType("MIXED_TICKETS");
        assertThrows(ServiceException.class,()->service.recordCase(row));
    }
}
