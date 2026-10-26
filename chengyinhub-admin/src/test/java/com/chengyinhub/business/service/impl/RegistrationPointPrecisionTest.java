package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.support.PointsValuePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Tests the real pricing/refund helpers; database rollback/locking remains a MySQL suite assertion. */
class RegistrationPointPrecisionTest {
    @Test
    void quoteRejectsUnrepresentablePointCountInsteadOfDiscountingForWrappedPoints() {
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        PointsValuePolicy policy = mock(PointsValuePolicy.class);
        when(policy.rate()).thenReturn(new BigDecimal("0.00000001"));
        when(policy.maxPerOrder()).thenReturn(new BigDecimal("50.00"));
        ReflectionTestUtils.setField(service, "pointsValuePolicy", policy);
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "computePrice", new BigDecimal("100.00"), 1, Long.MAX_VALUE, 1));
    }

    @Test
    void ordinaryQuoteKeepsExistingRateCapAndPointCount() {
        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "pointsValuePolicy", PointsValuePolicy.codeDefaults());
        Object price = ReflectionTestUtils.invokeMethod(service,
                "computePrice", new BigDecimal("100.00"), 1, 10000L, 1);
        assertNotNull(price);
        assertEquals(2500, ReflectionTestUtils.getField(price, "pointUsed"));
        assertEquals(0, new BigDecimal("50.00").compareTo(
                (BigDecimal) ReflectionTestUtils.getField(price, "payableAmount")));
    }

    @Test
    void bothReturnPathsRejectNegativeOrOverflowBeforeClaimingAnyReturnMarker() {
        for (boolean group : new boolean[]{false, true}) {
            for (long points : new long[]{-1L, 2147483648L, Long.MAX_VALUE}) {
                Fixture f = fixture(group);
                assertThrows(IllegalStateException.class,
                        () -> ReflectionTestUtils.invokeMethod(f.target(), f.method(), row(points)));
                verifyNoInteractions(f.registrations(), f.members());
            }
        }
    }

    @Test
    void zeroAndMissingPointsRemainNoopOnBothReturnPaths() {
        for (boolean group : new boolean[]{false, true}) {
            Fixture f = fixture(group);
            ReflectionTestUtils.invokeMethod(f.target(), f.method(), row(0L));
            ReflectionTestUtils.invokeMethod(f.target(), f.method(), row(null));
            verifyNoInteractions(f.registrations(), f.members());
        }
    }

    @Test
    void largestSupportedReturnRemainsExactAndCasLoserDoesNotAddAgain() {
        for (boolean group : new boolean[]{false, true}) {
            Fixture f = fixture(group);
            CmsRegistration row = row((long) Integer.MAX_VALUE);
            when(f.registrations().claimPointsReturn(eq(11L), anyString(), any())).thenReturn(1, 0);
            when(f.members().addPoints(71L, Integer.MAX_VALUE)).thenReturn(1);
            when(f.registrations().selectCmsRegistrationByIdForUpdate(11L)).thenReturn(row);
            ReflectionTestUtils.invokeMethod(f.target(), f.method(), row);
            row.setPointsReturned(1);
            ReflectionTestUtils.invokeMethod(f.target(), f.method(), row);
            verify(f.members(), times(1)).addPoints(71L, Integer.MAX_VALUE);
            verify(f.registrations(), times(2)).claimPointsReturn(eq(11L), anyString(), any());
        }
    }

    @Test
    void unknownOrNegativeCashCannotCancelSingleOrderOrReturnAnyInventoryOrPoints() {
        for (BigDecimal cash : new BigDecimal[]{null, new BigDecimal("-1")}) {
            Fixture f = fixture(true);
            CmsRegistration row = row(20L);
            row.setWechatPaymentAmount(cash);
            row.setPayableAmount(new BigDecimal("100.00"));
            row.setPaymentStatus(2);
            doReturn(row).when(f.registrations()).selectCmsRegistrationByIdForUpdate(row.getId());
            assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                    f.target(), "refundOne", row, "ticket", "cancel"));
            verify(f.registrations()).selectCmsRegistrationByIdForUpdate(row.getId());
            verifyNoMoreInteractions(f.registrations());
            verifyNoInteractions(f.members());
            // Other refund/inventory collaborators are deliberately not injected: reaching them would fail.
        }
    }

    @Test
    void unpaidCancelAndExpiredResourceReleaseAlsoRejectOverflowBeforeWriting() {
        for (long points : new long[]{-1L, 2147483648L, Long.MAX_VALUE}) {
            Fixture f = fixture(false);
            CmsRegistration row = row(points);
            row.setPaymentStatus(0);
            row.setPointsReturned(0);
            when(f.registrations().selectCmsRegistrationById(11L)).thenReturn(row);
            assertThrows(IllegalStateException.class, () -> ((CmsRegistrationServiceImpl) f.target())
                    .cancelRegistration(11L, 71L, "test actor"));
            assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                    f.target(), "releaseHeldResources", row, "ORGANIZER_CANCEL", "order-close-task"));
            verify(f.registrations()).selectCmsRegistrationById(11L);
            verifyNoMoreInteractions(f.registrations());
            verifyNoInteractions(f.members());
        }
    }

    @Test
    void parentRefundRejectsUnknownOrMultipleCashPayersBeforeAnyRefundSideEffect() {
        for (BigDecimal secondPayable : new BigDecimal[]{null, new BigDecimal("20")}) {
            Fixture f = fixture(true);
            CmsRegistration first = row(0L);
            CmsRegistration second = row(0L);
            second.setId(12L);
            for (CmsRegistration registration : new CmsRegistration[]{first, second}) {
                registration.setOwnerType(2L);
                registration.setOwnerId(55L);
                registration.setParentOrderNo("ORDER-ONE");
                registration.setPaymentStatus(2);
                registration.setPayableAmount(new BigDecimal("20"));
                registration.setWechatPaymentAmount(new BigDecimal("20"));
            }
            second.setPayableAmount(secondPayable);
            when(f.registrations().selectByParentOrderNoForUpdate("ORDER-ONE", 55L))
                    .thenReturn(java.util.List.of(first, second));
            assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                    f.target(), "refundWholeOrder", first, "ticket", "cancel", 71L));
            verify(f.registrations()).selectByParentOrderNoForUpdate("ORDER-ONE", 55L);
            verifyNoMoreInteractions(f.registrations());
            verifyNoInteractions(f.members());
        }
    }

    private Fixture fixture(boolean group) {
        Object target = group ? new TeamGroupServiceImpl() : new CmsRegistrationServiceImpl();
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(target, "cmsRegistrationMapper", registrations);
        ReflectionTestUtils.setField(target, "umsMemberMapper", members);
        return new Fixture(target, registrations, members, group ? "returnPoints" : "returnPointsToUser");
    }

    private CmsRegistration row(Long points) {
        CmsRegistration row = new CmsRegistration();
        row.setId(11L);
        row.setMemberId(71L);
        row.setPointUsed(points);
        return row;
    }

    private record Fixture(Object target, CmsRegistrationMapper registrations, UmsMemberMapper members, String method) {}
}
