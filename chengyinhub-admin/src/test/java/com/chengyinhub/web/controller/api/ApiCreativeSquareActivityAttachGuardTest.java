package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 广场发帖挂活动:只能挂自己主办或自己报名的 id。
 */
class ApiCreativeSquareActivityAttachGuardTest {

    private static final Long ME = 9L;
    private static final Long ACTIVITY = 101L;

    private static class StubController extends ApiCreativeSquareController {
        @Override
        public Long getAppUserId() {
            return ME;
        }
    }

    private StubController controller;
    private ICmsActivityService activityService;
    private ICmsRegistrationService registrationService;
    private IUmsCreativeSquareService squareService;

    @BeforeEach
    void setUp() {
        controller = new StubController();
        activityService = mock(ICmsActivityService.class);
        registrationService = mock(ICmsRegistrationService.class);
        squareService = mock(IUmsCreativeSquareService.class);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activityService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrationService);
        ReflectionTestUtils.setField(controller, "umsCreativeSquareService", squareService);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", mock(IWxContentSecurityService.class));
    }

    @Test
    void skipsWhenNotAnActivityAttach() {
        assertNull(controller.rejectForeignActivity(ME, 0L, 1L));
        assertNull(controller.rejectForeignActivity(ME, ACTIVITY, 3L));
        verify(activityService, never()).selectCmsActivityById(anyLong());
    }

    @Test
    void allowsHostedActivity() {
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(ME));
        assertNull(controller.rejectForeignActivity(ME, ACTIVITY, 1L));
        verify(registrationService, never()).selectCmsRegistrationList(any());
    }

    @Test
    void allowsJoinedActivity() {
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(88L));
        CmsRegistration row = new CmsRegistration();
        row.setOwnerId(ACTIVITY);
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(row));
        assertNull(controller.rejectForeignActivity(ME, ACTIVITY, 1L));
    }

    @Test
    void rejectsSomeoneElsesActivity() {
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(88L));
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());
        AjaxResult denied = controller.rejectForeignActivity(ME, ACTIVITY, 1L);
        assertEquals(500, denied.get(AjaxResult.CODE_TAG));
        assertEquals("只能关联自己主办或报名过的活动", denied.get(AjaxResult.MSG_TAG));
    }

    @Test
    void publishPathDoesNotInsertForeignActivity() {
        when(activityService.selectCmsActivityById(ACTIVITY)).thenReturn(activity(88L));
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());

        AjaxResult result = controller.creativeSquareActoin(
                null, "今晚去外滩", null, null, null, null, null, null,
                String.valueOf(ACTIVITY), "1", null);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(squareService, never()).insertUmsCreativeSquare(any(UmsCreativeSquare.class));
        verify(squareService, never()).updateUmsCreativeSquare(any(UmsCreativeSquare.class));
    }

    private static CmsActivity activity(Long ownerId) {
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setMemberId(ownerId);
        return activity;
    }
}
