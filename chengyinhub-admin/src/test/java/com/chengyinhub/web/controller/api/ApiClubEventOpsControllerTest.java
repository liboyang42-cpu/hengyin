package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.ClubWaitlistEntry;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.dto.ClubEventScopeRequest;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ClubWaitlistRequest;
import com.chengyinhub.business.domain.vo.ClubEventSeriesView;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.domain.vo.ClubEventCancellationStateVO;
import com.chengyinhub.business.service.IClubEventAttendanceService;
import com.chengyinhub.business.service.IClubEventNotificationService;
import com.chengyinhub.business.service.IClubEventOpsService;
import com.chengyinhub.business.service.IClubWaitlistService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

class ApiClubEventOpsControllerTest {
    @Test
    void seriesListAndDetailAreScopedToAuthenticatedMemberAndClub() {
        IClubEventOpsService ops = mock(IClubEventOpsService.class);
        ApiClubEventOpsController controller = new ApiClubEventOpsController() {
            @Override public Long getAppUserId() { return 41L; }
        };
        ReflectionTestUtils.setField(controller, "eventOpsService", ops);
        ReflectionTestUtils.setField(controller, "waitlistService", mock(IClubWaitlistService.class));
        ReflectionTestUtils.setField(controller, "attendanceService", mock(IClubEventAttendanceService.class));
        ClubEventSeriesView view = new ClubEventSeriesView();
        view.setId(61L);
        when(ops.listSeries(41L, 21L)).thenReturn(Collections.singletonList(view));
        when(ops.getSeries(41L, 21L, 61L)).thenReturn(view);

        java.util.Map<String, Object> listRequest = new java.util.HashMap<>();
        listRequest.put("clubId", 21L);
        AjaxResult listed = controller.listSeries(listRequest);
        assertEquals(200, listed.get("code"));
        verify(ops).listSeries(41L, 21L);

        ClubEventSeriesRequest detailRequest = new ClubEventSeriesRequest();
        detailRequest.setClubId(21L);
        detailRequest.setSeriesId(61L);
        AjaxResult detailed = controller.seriesDetail(detailRequest);
        assertEquals(200, detailed.get("code"));
        verify(ops).getSeries(41L, 21L, 61L);
    }

    @Test
    void seriesAndWaitlistUseAuthenticatedMemberInsteadOfClientActor() {
        IClubEventOpsService ops = mock(IClubEventOpsService.class);
        IClubWaitlistService waitlist = mock(IClubWaitlistService.class);
        ApiClubEventOpsController controller = new ApiClubEventOpsController() {
            @Override public Long getAppUserId() { return 41L; }
            @Override public String getAppUserName() { return "operator"; }
        };
        ReflectionTestUtils.setField(controller, "eventOpsService", ops);
        ReflectionTestUtils.setField(controller, "waitlistService", waitlist);
        ReflectionTestUtils.setField(controller, "attendanceService", mock(IClubEventAttendanceService.class));

        ClubEventSeriesRequest seriesRequest = new ClubEventSeriesRequest();
        ClubEventSeries series = new ClubEventSeries();
        series.setId(61L);
        when(ops.createSeries(41L, "operator", seriesRequest)).thenReturn(series);
        AjaxResult created = controller.createSeries(seriesRequest);
        assertEquals(200, created.get("code"));
        verify(ops).createSeries(41L, "operator", seriesRequest);

        ClubWaitlistRequest request = new ClubWaitlistRequest();
        request.setActivityId(71L);
        request.setTicketId(81L);
        ClubWaitlistEntry entry = new ClubWaitlistEntry();
        entry.setId(91L);
        when(waitlist.join(41L, 71L, 81L)).thenReturn(entry);
        assertEquals(200, controller.joinWaitlist(request).get("code"));
        verify(waitlist).join(eq(41L), eq(71L), eq(81L));
    }

    @Test
    void notificationControllerScopesRetryToAuthenticatedActor() {
        IClubEventNotificationService service = mock(IClubEventNotificationService.class);
        ApiClubEventNotificationController controller = new ApiClubEventNotificationController() {
            @Override public Long getAppUserId() { return 41L; }
        };
        ReflectionTestUtils.setField(controller, "notificationService", service);
        ClubEventCampaignRequest request = new ClubEventCampaignRequest();
        ClubEventCampaign campaign = new ClubEventCampaign();
        campaign.setId(101L);
        when(service.send(41L, request)).thenReturn(campaign);

        assertEquals(200, controller.send(request).get("code"));
        verify(service).send(41L, request);
    }

    @Test
    void cancellationAndIndependentStatusReadbackUseAuthenticatedActorAndExactActivityScope() {
        IClubEventOpsService ops = mock(IClubEventOpsService.class);
        ApiClubEventOpsController controller = new ApiClubEventOpsController() {
            @Override public Long getAppUserId() { return 41L; }
            @Override public String getAppUserName() { return "co-owner"; }
        };
        ReflectionTestUtils.setField(controller, "eventOpsService", ops);
        ReflectionTestUtils.setField(controller, "waitlistService", mock(IClubWaitlistService.class));
        ReflectionTestUtils.setField(controller, "attendanceService", mock(IClubEventAttendanceService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ClubEventCancellationRequest cancellation = new ClubEventCancellationRequest();
        cancellation.setClubId(21L);
        cancellation.setActivityId(71L);
        cancellation.setReason("场地临时关闭");
        cancellation.setRequestId("event-cancel-1");
        ClubEventCancellationResult accepted = new ClubEventCancellationResult();
        accepted.setActivityId(71L);
        when(ops.cancelOccurrence(41L, "co-owner", cancellation)).thenReturn(accepted);
        ClubEventScopeRequest scope = new ClubEventScopeRequest();
        scope.setClubId(21L);
        scope.setActivityId(71L);
        ClubEventCancellationStateVO state = new ClubEventCancellationStateVO();
        state.setClubId(21L);
        state.setActivityId(71L);
        state.setOccurrenceStatus("CANCELLED");
        state.setActivityCancelled(true);
        state.setPublishStatus(0L);
        when(ops.getCancellationState(41L, 21L, 71L)).thenReturn(state);

        assertEquals(200, controller.cancelOccurrence(cancellation).get("code"));
        assertEquals(200, controller.occurrenceStatus(scope).get("code"));
        verify(ops).cancelOccurrence(41L, "co-owner", cancellation);
        verify(ops).getCancellationState(41L, 21L, 71L);
    }

    /** F-L5C-02:取消原因会发给报名者,送检拦截时不得进入取消流程。 */
    @Test
    void flaggedCancellationReasonIsRejectedBeforeCancellation() {
        IClubEventOpsService ops = mock(IClubEventOpsService.class);
        ApiClubEventOpsController controller = new ApiClubEventOpsController() {
            @Override public Long getAppUserId() { return 41L; }
            @Override public String getAppUserName() { return "co-owner"; }
        };
        com.chengyinhub.business.service.IWxContentSecurityService sec =
                mock(com.chengyinhub.business.service.IWxContentSecurityService.class);
        when(sec.checkText(eq("违规原因"), org.mockito.ArgumentMatchers.any())).thenReturn("内容含违规信息");
        ReflectionTestUtils.setField(controller, "eventOpsService", ops);
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(com.chengyinhub.business.service.IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", sec);
        ClubEventCancellationRequest cancellation = new ClubEventCancellationRequest();
        cancellation.setClubId(21L);
        cancellation.setActivityId(71L);
        cancellation.setReason("违规原因");

        assertEquals(500, controller.cancelOccurrence(cancellation).get("code"));
        verify(ops, org.mockito.Mockito.never()).cancelOccurrence(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
