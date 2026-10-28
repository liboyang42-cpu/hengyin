package com.chengyinhub.web.service.registration;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.RegistrationSeatService;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.controller.api.RegistrationRedemptionTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adapter/owner boundary checks; the original twelve suites retain their business cases.
 * Mapper, transaction, concurrency and HTTP/JWT proof remain with controlled integration tests. */
class RegistrationRedemptionApplicationTest {
    private static final String SECRET = "redemption-application-test-secret";
    private ApiRegistrationController controller;
    private RegistrationRedemptionApplication application;
    private ICmsRegistrationService registrations;
    private ICmsActivityService activities;
    private RegistrationSeatService seats;
    private IPointsAwardService points;
    private IGrowthService growth;

    @BeforeEach void setup() {
        controller = spy(new ApiRegistrationController());
        doReturn(81L).when(controller).getAppUserId();
        application = RegistrationRedemptionTestFixtures.application(controller);
        registrations = mock(ICmsRegistrationService.class);
        activities = mock(ICmsActivityService.class);
        seats = mock(RegistrationSeatService.class);
        points = mock(IPointsAwardService.class);
        growth = mock(IGrowthService.class);
        ReflectionTestUtils.setField(application, "dynSecret", SECRET);
        ReflectionTestUtils.setField(application, "registrationService", registrations);
        ReflectionTestUtils.setField(application, "cmsActivityService", activities);
        ReflectionTestUtils.setField(application, "registrationSeatService", seats);
        ReflectionTestUtils.setField(application, "pointsAwardService", points);
        ReflectionTestUtils.setField(application, "growthService", growth);
    }

    @Test void expiredOriginalSeatReadbackKeepsOneCapturedActorAndNeverExecutesRewards() {
        doReturn(81L, 999L).when(controller).getAppUserId();
        String code = VerifyDynCodeUtil.issue(31L, "activity_seat_2",
                System.currentTimeMillis() - 600000, 300000, 7, SECRET);
        var original = new RegistrationSeatService.Receipt(102L, 31L, 2, 41L, 61L, 81L,
                new Date(1700000000000L), true);
        when(seats.verifierSeatReceipt(31L, 2, null, 81L, true)).thenReturn(original);

        AjaxResult response = controller.scanDynamicCode(code);

        assertEquals(200, response.get("code"));
        assertSame(original, response.get("data"));
        assertEquals("该席位已核销，请勿重复提供权益", response.get("msg"));
        verify(controller, times(1)).getAppUserId();
        verify(seats).verifierSeatReceipt(31L, 2, null, 81L, true);
        verifyNoMoreInteractions(seats);
        verifyNoInteractions(registrations, activities, points, growth);
    }

    @Test void authenticationFailureKeepsDynamicInputAndChapterRefusalsAtTheirOriginalLayers() {
        doThrow(new ServiceException("登录上下文不可用")).when(controller).getAppUserId();
        assertEquals("核销码无效", controller.scanDynamicCode("").get("msg"));
        assertEquals("请先登录", controller.scanQrCodeChapter("original", 42L).get("msg"));
        assertEquals("请先登录", controller.scanQrCode("activity", "original").get("msg"));
        String seatCode = VerifyDynCodeUtil.issue(31L, "topic_seat_2", System.currentTimeMillis(), 300000, 7, SECRET);
        assertEquals("请先登录", controller.scanGroupMemberTicket(seatCode, 61L).get("msg"));
        verifyNoInteractions(registrations, activities, seats, points, growth);
    }

    @Test void activitySettlementStillPrecedesRewardsAndDuplicateKeepsItsOriginalResponse() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(31L); registration.setMemberId(51L);
        registration.setOwnerType(2L); registration.setOwnerId(61L);
        registration.setPaymentStatus(2); registration.setPurchaseKind(PurchaseKind.GUIDED_TICKET);
        CmsActivity activity = new CmsActivity(); activity.setId(61L); activity.setMemberId(81L);
        when(registrations.selectByverificationCode("original-static")).thenReturn(registration);
        when(activities.selectCmsActivityById(61L)).thenReturn(activity);
        when(registrations.verifyAndSettle(registration, 81L, 81L, null)).thenReturn(true, false);

        assertEquals("核销成功", controller.scanQrCode("activity", "original-static").get("msg"));
        AjaxResult duplicate = controller.scanQrCode("activity", "original-static");
        assertEquals(500, duplicate.get("code"));
        assertEquals("该订单已核销", duplicate.get("msg"));
        assertEquals(true, ((Map<?, ?>) duplicate.get("data")).get("alreadyVerified"));
        InOrder order = inOrder(registrations, points, growth);
        order.verify(registrations).verifyAndSettle(registration, 81L, 81L, null);
        order.verify(points).award(51L, 11L, 31L, "到店核销");
        order.verify(growth).deliverStoreVerification(31L);
        order.verify(registrations).verifyAndSettle(registration, 81L, 81L, null);
        order.verify(growth).deliverStoreVerification(31L);
        verify(points, times(1)).award(51L, 11L, 31L, "到店核销");
        verifyNoInteractions(seats);
    }

    @Test void applicationDoesNotIntroduceAnOuterTransactionAroundExistingCommandAndRewardBoundaries() {
        assertFalse(RegistrationRedemptionApplication.class.isAnnotationPresent(Transactional.class));
        for (var method : RegistrationRedemptionApplication.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Transactional.class), method.toString());
        }
    }
}
