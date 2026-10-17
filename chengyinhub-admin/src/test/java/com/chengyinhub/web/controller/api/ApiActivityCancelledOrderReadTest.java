package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IViewRegistrationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiActivityCancelledOrderReadTest {
    @Test void cancelledTicketHolderSeesCancellationWithoutPrivateDetails() {
        AjaxResult result = read(20L, true, new Date(), 0);
        assertEquals("活动已取消，请在订单中查看退款或售后进度", result.get("msg"));
        assertFalse(result.containsKey("data"));
        assertEquals("ACTIVITY_CANCELLED", result.get("errorCode"));
        assertEquals(true, result.get("canViewOwnOrders"));
    }
    @Test void cancelledOwnerSeesCancellation() {
        AjaxResult result = read(10L, false, new Date(), 0);
        assertEquals("活动已取消，请在订单中查看退款或售后进度", result.get("msg"));
        assertEquals(false, result.get("canViewOwnOrders"));
        assertFalse(result.containsKey("data"));
    }
    @Test void unrelatedOrAnonymousCannotDiscoverCancelledPrivateActivity() {
        assertEquals("活动不存在", read(30L, false, new Date(), 0).get("msg"));
        assertEquals("活动不存在", read(0L, false, new Date(), 0).get("msg"));
    }
    @Test void deletedOrSimplyUnavailableActivityKeepsExistingBoundary() {
        assertEquals("活动不存在", read(20L, true, new Date(), 2).get("msg"));
        assertEquals("活动不存在", read(20L, true, null, 0).get("msg"));
    }
    private AjaxResult read(Long actor, boolean hasTicket, Date cancelledAt, Integer deleted) {
        ICmsActivityService activities = mock(ICmsActivityService.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(99L); activity.setMemberId(10L); activity.setCancelTime(cancelledAt); activity.setDelFlag(deleted);
        when(activities.selectCmsActivityById(99L)).thenReturn(activity);
        IViewRegistrationService registrations = mock(IViewRegistrationService.class);
        when(registrations.selectRegistrationSummaries(any())).thenAnswer(call -> {
            ViewRegistration query = call.getArgument(0);
            assertEquals(actor, query.getMemberId()); assertEquals(2L, query.getOwnerType()); assertEquals(99L, query.getOwnerId());
            return hasTicket ? Collections.singletonList(new ViewRegistration()) : Collections.emptyList();
        });
        ApiActivityController controller = spy(new ApiActivityController());
        doReturn(actor).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "viewRegistrationService", registrations);
        AjaxResult result = controller.activityInfo("99");
        if (actor == 0L || deleted == 2 || cancelledAt == null) verifyNoInteractions(registrations);
        if ("活动不存在".equals(result.get("msg"))) {
            assertFalse(result.containsKey("errorCode"));
            assertFalse(result.containsKey("canViewOwnOrders"));
        }
        return result;
    }
}
