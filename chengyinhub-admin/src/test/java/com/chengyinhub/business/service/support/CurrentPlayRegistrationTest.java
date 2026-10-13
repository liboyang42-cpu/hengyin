package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import java.util.Arrays;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CurrentPlayRegistrationTest {
    private CmsActivityMapper activities;
    private CmsRegistrationMapper registrations;
    private CmsActivity activity;
    private CmsRegistration current;
    private final Date now=new Date(10000L);
    @BeforeEach void setup() {
        activities=mock(CmsActivityMapper.class);registrations=mock(CmsRegistrationMapper.class);
        activity=new CmsActivity();activity.setId(9L);activity.setEndDate(new Date(11000L));
        current=new CmsRegistration();current.setId(19L);current.setMemberId(7L);current.setOwnerType(2L);
        current.setOwnerId(9L);current.setRegistrationStatus(2);current.setPaymentStatus(2);current.setPurchaseKind(3);
        when(activities.selectCmsActivityByIdForUpdate(9L)).thenReturn(activity);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Arrays.asList(current));
        when(registrations.selectCmsRegistrationByIdForUpdate(19L)).thenReturn(current);
    }
    private CmsRegistration use() { return CurrentPlayRegistration.require(activities,registrations,7L,2L,9L,null,now); }
    @Test void validBeforeEndLocksActivityThenCurrentOriginalRegistration() {
        assertSame(current,use());InOrder locks=inOrder(activities,registrations);
        locks.verify(activities).selectCmsActivityByIdForUpdate(9L);
        locks.verify(registrations).selectCmsRegistrationList(any());
        locks.verify(registrations).selectCmsRegistrationByIdForUpdate(19L);
    }
    @Test void exactlyAtEndAndAfterEndRejectNewUse() {
        activity.setEndDate(now);assertThrows(IllegalStateException.class,this::use);
        activity.setEndDate(new Date(9000L));assertThrows(IllegalStateException.class,this::use);
    }
    @Test void missingEndRejectsUntilExistingActivityDateIsCorrected() {
        activity.setEndDate(null);assertThrows(IllegalStateException.class,this::use);
        activity.setEndDate(new Date(12000L));assertSame(current,use());
    }
    @Test void currentRefundCannotReusePreviouslyPaidSnapshot() {
        CmsRegistration refunded=new CmsRegistration();refunded.setId(19L);refunded.setMemberId(7L);
        refunded.setOwnerType(2L);refunded.setOwnerId(9L);refunded.setRegistrationStatus(2);refunded.setPaymentStatus(4);refunded.setPurchaseKind(3);
        when(registrations.selectCmsRegistrationByIdForUpdate(19L)).thenReturn(refunded);
        assertThrows(IllegalStateException.class,this::use);
    }
    @Test void payingValueOneIsNeverPaid() {
        current.setPaymentStatus(1);assertThrows(IllegalStateException.class,this::use);
    }
    @Test void changedMemberOrOwnerCannotUseOldSnapshot() {
        current.setMemberId(8L);assertThrows(IllegalStateException.class,this::use);
        current.setMemberId(7L);current.setOwnerId(10L);assertThrows(IllegalStateException.class,this::use);
    }
    @Test void explicitOriginalRegistrationDoesNotFallBackToAnotherTicket() {
        assertThrows(IllegalStateException.class,()->CurrentPlayRegistration.require(activities,registrations,7L,2L,9L,20L,now));
        verify(registrations,never()).selectCmsRegistrationByIdForUpdate(anyLong());
    }
    @Test void ordinaryTicketKeepsOriginalMissingDateBehavior() {
        current.setPurchaseKind(1);activity.setEndDate(null);assertSame(current,use());
    }
    @Test void expiredSelfPlayEntitlementDoesNotUsePaymentDeadline() {
        current.setOwnerType(1L);current.setOwnerId(9L);current.setPurchaseKind(2);current.setExpiresAt(now);
        assertThrows(IllegalStateException.class,()->CurrentPlayRegistration.require(activities,registrations,7L,1L,9L,null,now));
        current.setExpiresAt(new Date(11000L));
        assertSame(current,CurrentPlayRegistration.require(activities,registrations,7L,1L,9L,null,now));
        verifyNoInteractions(activities);
    }
    @Test void ordinaryFreeRegistrationKeepsItsOriginalStatusAndOwnerQualification() {
        current.setPurchaseKind(1);current.setPaymentStatus(0);activity.setEndDate(null);
        assertSame(current,use());
    }
    @Test void onlyOriginallyPaidConsumerEnforcesPaymentOnOrdinaryRegistration() {
        current.setPurchaseKind(1);current.setPaymentStatus(0);
        assertThrows(IllegalStateException.class,()->CurrentPlayRegistration.require(
                activities,registrations,7L,2L,9L,null,now,true));
    }

}
