package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundFulfillmentViewTest {
    @Test
    void originalLargeIdRemainsExactAndMissingSourceRemainsUnknown() {
        Fixture f = fixture();
        var view = f.service.view(9007199254740993L);
        assertEquals("9007199254740993", view.get("refundId"));
        assertEquals(true, view.get("scopeUnknown"));
        verifyNoInteractions(f.holds);
    }

    @Test
    void replacementHoldKeepsActualNewIntentRatherThanClaimingOldIntentCompletion() {
        Fixture f = fixture();
        CmsRegistration row = row(99L); when(f.registrations.selectCmsRegistrationById(99L)).thenReturn(row);
        ExploreBundleRefundHold hold = new ExploreBundleRefundHold();
        hold.setRegistrationId(99L); hold.setRefundId(9007199254740994L); hold.setState("RECONCILIATION_REQUIRED");
        when(f.holds.selectByRegistration(99L)).thenReturn(hold);
        var result = f.service.view(9007199254740993L);
        assertEquals("9007199254740993", result.get("refundId"));
        assertEquals(hold, ((List<?>) result.get("holds")).get(0));
        assertEquals(Long.valueOf(9007199254740994L), hold.getRefundId());
        verify(f.holds, never()).update(any());
        verify(f.refunds, never()).updatePayoutResult(anyLong(), anyInt(), anyString());
    }

    @Test
    void foreignParentScopeIsRejectedBeforeReadingAnotherHoldersFreeze() {
        Fixture f = fixture(); CmsRegistration source = row(99L); source.setParentOrderNo("P");
        CmsRegistration foreign = row(100L); foreign.setParentOrderNo("P"); foreign.setOwnerId(30L);
        when(f.registrations.selectCmsRegistrationById(99L)).thenReturn(source);
        when(f.registrations.selectByParentOrderNo("P")).thenReturn(List.of(source, foreign));
        assertThrows(RuntimeException.class, () -> f.service.view(9007199254740993L));
        verifyNoInteractions(f.holds);
    }

    @Test
    void wrongRegistrationProjectionIsNeverDisplayedAsOriginalHold() {
        Fixture f = fixture(); when(f.registrations.selectCmsRegistrationById(99L)).thenReturn(row(99L));
        ExploreBundleRefundHold hold = new ExploreBundleRefundHold(); hold.setRegistrationId(100L);
        when(f.holds.selectByRegistration(99L)).thenReturn(hold);
        assertThrows(IllegalStateException.class, () -> f.service.view(9007199254740993L));
    }

    private CmsRegistration row(Long id) {
        CmsRegistration row = new CmsRegistration(); row.setId(id); row.setOwnerType(2L); row.setOwnerId(20L); row.setMemberId(9L);
        return row;
    }
    private Fixture fixture() {
        RefundApplicationMapper refunds = mock(RefundApplicationMapper.class);
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        ExploreBundleRefundMapper holds = mock(ExploreBundleRefundMapper.class);
        RefundApplication refund = new RefundApplication(); refund.setSourceType("registration"); refund.setSourceId(99L); refund.setDelFlag(0);
        when(refunds.selectRefundApplicationById(9007199254740993L)).thenReturn(refund);
        RegistrationRefundFulfillmentViewService service = new RegistrationRefundFulfillmentViewService();
        ReflectionTestUtils.setField(service, "refundApplicationMapper", refunds);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(service, "exploreBundleRefundMapper", holds);
        return new Fixture(service, refunds, registrations, holds);
    }
    private record Fixture(RegistrationRefundFulfillmentViewService service, RefundApplicationMapper refunds,
                           CmsRegistrationMapper registrations, ExploreBundleRefundMapper holds) { }
}
