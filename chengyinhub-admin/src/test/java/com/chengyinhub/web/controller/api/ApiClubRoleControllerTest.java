package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubEventAccess;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.dto.ClubAccessRequest;
import com.chengyinhub.business.domain.dto.ClubRoleAssignRequest;
import com.chengyinhub.business.domain.dto.ClubRoleRevokeRequest;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiClubRoleControllerTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long CLUB = 11L;
    private static final long EVENT = 55L;
    private static final long TOPIC = 501L;

    @Test
    void accessMeReturnsWhitelistedClubAndEventScopedPermissions() throws Exception {
        IClubAccessService access = mock(IClubAccessService.class);
        IClubService clubs = mock(IClubService.class);
        ClubAccessContext context = new ClubAccessContext(PRINCIPAL, CLUB, OWNER, EVENT,
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                        ClubRoleCode.CLUB_MEMBER, ClubRoleCode.EVENT_CHECKIN)));
        when(access.resolve(PRINCIPAL, CLUB, EVENT)).thenReturn(context);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        club.setName("夜跑俱乐部");
        club.setLogo("logo.png");
        club.setPhone("13800000000");
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        ApiClubRoleController controller = controller(access, mock(IClubRoleAssignmentService.class), clubs);
        ClubAccessRequest request = new ClubAccessRequest();
        request.setClubId(CLUB);
        request.setActivityId(EVENT);

        AjaxResult result = controller.accessMe(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertTrue(json.contains(ClubRoleCode.EVENT_CHECKIN));
        assertTrue(json.contains("club:event:checkin"));
        assertFalse(json.contains("club:finance:read"));
        assertFalse(json.contains("13800000000"));
        assertFalse(json.contains(String.valueOf(OWNER)), "access/me 不下发 owner memberId");
    }

    @Test
    void assignForwardsClientScopeToServiceForServerValidation() {
        IClubRoleAssignmentService service = mock(IClubRoleAssignmentService.class);
        ClubRoleAssignment assigned = new ClubRoleAssignment();
        assigned.setId(81L);
        assigned.setClubId(CLUB);
        assigned.setTargetMemberId(902L);
        assigned.setRoleCode(ClubRoleCode.EVENT_LEAD);
        assigned.setScopeType(ClubRoleAssignment.SCOPE_EVENT);
        assigned.setScopeId(EVENT);
        assigned.setStatus(ClubRoleAssignment.STATUS_ACTIVE);
        assigned.setVersion(0);
        when(service.assign(PRINCIPAL, CLUB, 902L, ClubRoleCode.EVENT_LEAD,
                EVENT, null, "req-event")).thenReturn(assigned);
        ApiClubRoleController controller = controller(
                mock(IClubAccessService.class), service, mock(IClubService.class));
        ClubRoleAssignRequest request = new ClubRoleAssignRequest();
        request.setClubId(CLUB);
        request.setTargetMemberId(902L);
        request.setRoleCode(ClubRoleCode.EVENT_LEAD);
        request.setActivityId(EVENT);
        request.setRequestId("req-event");

        AjaxResult result = controller.assign(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(service).assign(PRINCIPAL, CLUB, 902L, ClubRoleCode.EVENT_LEAD,
                EVENT, null, "req-event");
    }

    @Test
    void roleWritesRequireClientRequestIdBeforeCallingService() {
        IClubRoleAssignmentService service = mock(IClubRoleAssignmentService.class);
        ApiClubRoleController controller = controller(
                mock(IClubAccessService.class), service, mock(IClubService.class));
        ClubRoleAssignRequest assign = new ClubRoleAssignRequest();
        assign.setClubId(CLUB);
        assign.setTargetMemberId(902L);
        assign.setRoleCode(ClubRoleCode.CLUB_OPERATOR);
        ClubRoleRevokeRequest revoke = new ClubRoleRevokeRequest();
        revoke.setClubId(CLUB);
        revoke.setAssignmentId(81L);
        revoke.setVersion(0);
        revoke.setReason("排班变化");

        assertEquals(400, controller.assign(assign).get(AjaxResult.CODE_TAG));
        assertEquals(400, controller.revoke(revoke).get(AjaxResult.CODE_TAG));
        verify(service, never()).assign(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(service, never()).revoke(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void accessMeReturnsExplicitInactiveForNonMember() {
        IClubAccessService access = mock(IClubAccessService.class);
        when(access.resolveWithEventAccesses(PRINCIPAL, CLUB)).thenReturn(null);
        ApiClubRoleController controller = controller(
                access, mock(IClubRoleAssignmentService.class), mock(IClubService.class));
        ClubAccessRequest request = new ClubAccessRequest();
        request.setClubId(CLUB);

        AjaxResult result = controller.accessMe(request);

        assertEquals(Boolean.FALSE, ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("active"));
        assertEquals(Collections.emptyList(),
                ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("permissions"));
        assertEquals(Collections.emptyList(),
                ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("eventAccesses"));
    }

    @Test
    void clubScopedAccessPublishesOnlyScopedEventRolesAndPermissions() {
        IClubAccessService access = mock(IClubAccessService.class);
        IClubService clubs = mock(IClubService.class);
        when(access.resolveWithEventAccesses(PRINCIPAL, CLUB)).thenReturn(new ClubAccessContext(
                PRINCIPAL, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_MEMBER),
                Collections.singletonList(new ClubEventAccess(EVENT, TOPIC,
                        Collections.singleton(ClubRoleCode.EVENT_CHECKIN)))));
        Club club = new Club();
        club.setId(CLUB);
        club.setName("夜跑俱乐部");
        when(clubs.selectClubById(CLUB)).thenReturn(club);
        ApiClubRoleController controller = controller(
                access, mock(IClubRoleAssignmentService.class), clubs);
        ClubAccessRequest request = new ClubAccessRequest();
        request.setClubId(CLUB);

        AjaxResult result = controller.accessMe(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eventAccesses =
                (List<Map<String, Object>>) data.get("eventAccesses");
        assertEquals(1, eventAccesses.size());
        assertEquals(EVENT, eventAccesses.get(0).get("activityId"));
        assertEquals(TOPIC, eventAccesses.get(0).get("topicId"));
        assertEquals(Collections.singletonList(ClubRoleCode.EVENT_CHECKIN),
                eventAccesses.get(0).get("roleCodes"));
        assertEquals(Arrays.asList(ClubPermission.CLUB_READ, ClubPermission.ACTIVITY_READ,
                        ClubPermission.EVENT_CHECKIN),
                eventAccesses.get(0).get("permissions"));
        assertFalse(eventAccesses.get(0).containsKey("ownerMemberId"));
        assertFalse(eventAccesses.get(0).toString().contains(ClubPermission.EVENT_OPERATE));
        assertFalse(eventAccesses.get(0).toString().contains(ClubPermission.FINANCE_READ));
    }

    private static ApiClubRoleController controller(IClubAccessService access,
                                                     IClubRoleAssignmentService roles,
                                                     IClubService clubs) {
        ApiClubRoleController controller = new ApiClubRoleController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "clubAccessService", access);
        ReflectionTestUtils.setField(controller, "clubRoleAssignmentService", roles);
        ReflectionTestUtils.setField(controller, "clubService", clubs);
        return controller;
    }
}
