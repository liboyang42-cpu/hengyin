package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RegistrationCurrentUseGateTest {
    private CmsRegistrationMapper registrations;
    private CmsActivityMapper activities;
    private CmsRegistrationServiceImpl service;
    private CmsRegistration snapshot, current;
    private CmsActivity activity;
    @BeforeEach void setup() {
        registrations = mock(CmsRegistrationMapper.class); activities = mock(CmsActivityMapper.class);
        service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        snapshot = row(); current = row();
        activity = new CmsActivity(); activity.setId(20L); activity.setTopicId(30L);
        activity.setEndDate(new Date(System.currentTimeMillis() + 86400000L));
        when(activities.selectCmsActivityByIdForUpdate(20L)).thenReturn(activity);
        when(registrations.selectCmsRegistrationByIdForUpdate(10L)).thenReturn(current);
        when(registrations.selectCmsRegistrationById(10L)).thenReturn(snapshot);
    }
    private CmsRegistration row() {
        CmsRegistration r = new CmsRegistration(); r.setId(10L); r.setMemberId(40L);
        r.setOwnerType(2L); r.setOwnerId(20L); r.setPurchaseKind(3);
        r.setPaymentStatus(2); r.setRegistrationStatus(2); return r;
    }
    @Test void returnedObjectUsesCurrentPaidFactsAndActivityLocksFirst() {
        assertSame(current, service.requireCurrentUsableRegistration(snapshot));
        org.mockito.InOrder order = inOrder(activities, registrations);
        order.verify(activities).selectCmsActivityByIdForUpdate(20L);
        order.verify(registrations).selectCmsRegistrationByIdForUpdate(10L);
    }
    @Test void oldPaidSnapshotCannotMintAfterRefund() {
        current.setPaymentStatus(4);
        assertThrows(IllegalStateException.class, () -> service.generateVerificationQrcode(10L));
        // Exercise the explicitly supplied old paid snapshot as well.
        assertThrows(IllegalStateException.class, () -> service.requireCurrentUsableRegistration(snapshot));
        verify(registrations, never()).updateCmsRegistration(any());
    }
    @Test void oldSnapshotCannotUseChangedMemberOrOwner() {
        current.setMemberId(41L);
        assertThrows(IllegalStateException.class, () -> service.requireCurrentUsableRegistration(snapshot));
        current = row(); current.setOwnerId(21L);
        when(registrations.selectCmsRegistrationByIdForUpdate(10L)).thenReturn(current);
        assertThrows(IllegalStateException.class, () -> service.requireCurrentUsableRegistration(snapshot));
    }
    @Test void missingEndRejectsAndRestoredEndAllowsOriginalTicket() {
        activity.setEndDate(null);
        assertThrows(IllegalStateException.class, () -> service.requireCurrentUsableRegistration(snapshot));
        activity.setEndDate(new Date(System.currentTimeMillis() + 86400000L));
        assertSame(current, service.requireCurrentUsableRegistration(snapshot));
    }
    @Test void expiredOldStaticUrlCannotBeReturnedOrWritten() {
        current.setQrcodeUrl("existing-original-code");
        when(registrations.selectCmsRegistrationById(10L)).thenReturn(snapshot);
        activity.setEndDate(new Date(1));
        assertThrows(IllegalStateException.class, () -> service.generateVerificationQrcode(10L));
        verify(registrations, never()).updateCmsRegistration(any());
    }
    @Test void nonExploreKeepsPriorEndDateBehavior() {
        current.setPurchaseKind(1); activity.setEndDate(null);
        assertSame(current, service.requireCurrentUsableRegistration(snapshot));
    }
    @Test void chapterRedemptionRejectsOldPaidSnapshotBeforeAnyResourceMutation() {
        current.setPaymentStatus(4);
        IPerkLedgerService perks = mock(IPerkLedgerService.class);
        ReflectionTestUtils.setField(service, "perkLedgerService", perks);
        assertThrows(IllegalStateException.class, () -> service.redeemExploreChapterAndRecord(
                snapshot, 50L, 30L, 60L, 60L, null));
        verify(perks, never()).redeemChapter(anyLong(), anyLong(), anyLong(), anyLong());
        verify(registrations, never()).verifyIfNotVerified(anyLong(), anyLong());
    }
}
