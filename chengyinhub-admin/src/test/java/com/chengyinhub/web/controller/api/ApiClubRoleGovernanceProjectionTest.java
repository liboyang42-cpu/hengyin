package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.ClubRoleGovernanceMetadata;
import com.chengyinhub.business.domain.ClubRoleAssignment;
import com.chengyinhub.business.domain.dto.ClubAccessRequest;
import com.chengyinhub.business.service.IClubRoleAssignmentService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiClubRoleGovernanceProjectionTest {
    private static final long OWNER_ID = 61L;
    private static final long CLUB_ID = 91L;
    private static final int DISTINCT_MEMBER_COUNT = 2;

    @Test
    @SuppressWarnings("unchecked")
    void roleCatalogReturnsLevelDistinctMemberCountAndPermissionKeys() {
        IClubRoleAssignmentService service = mock(IClubRoleAssignmentService.class);
        ApiClubRoleController controller = new TestableController(OWNER_ID);
        ReflectionTestUtils.setField(controller, "clubRoleAssignmentService", service);
        ClubAccessRequest request = new ClubAccessRequest();
        request.setClubId(CLUB_ID);
        when(service.list(OWNER_ID, CLUB_ID, null)).thenReturn(Arrays.asList(
                assignment(101L), assignment(101L), assignment(102L)));

        AjaxResult result = controller.list(request);
        Map<String, Object> data = (Map<String, Object>) result.get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> roles = (List<Map<String, Object>>) data.get("roles");
        Map<String, Object> coOwner = roles.stream()
                .filter(role -> ClubRoleCode.CLUB_CO_OWNER.equals(role.get("roleId")))
                .findFirst().orElseThrow(AssertionError::new);

        assertEquals(ClubRoleGovernanceMetadata.LEVEL_L3, coOwner.get("permissionLevel"));
        assertEquals(DISTINCT_MEMBER_COUNT, coOwner.get("memberCount"));
        assertEquals(coOwner.get("permissions"), coOwner.get("permissionKeys"));
    }

    /** U-013:前端按目录的 assignable 筛角色;目录必须恰好是服务端可分配集合且逐行带 assignable=true。 */
    @Test
    @SuppressWarnings("unchecked")
    void roleCatalogMarksEveryRowAssignableAndMatchesServerSet() {
        IClubRoleAssignmentService service = mock(IClubRoleAssignmentService.class);
        ApiClubRoleController controller = new TestableController(OWNER_ID);
        ReflectionTestUtils.setField(controller, "clubRoleAssignmentService", service);
        ClubAccessRequest request = new ClubAccessRequest();
        request.setClubId(CLUB_ID);
        when(service.list(OWNER_ID, CLUB_ID, null)).thenReturn(java.util.Collections.emptyList());

        Map<String, Object> data = (Map<String, Object>) controller.list(request).get(AjaxResult.DATA_TAG);
        List<Map<String, Object>> roles = (List<Map<String, Object>>) data.get("roles");
        assertEquals(new java.util.ArrayList<>(ClubRoleCode.assignableRoles()),
                roles.stream().map(role -> role.get("roleCode")).collect(java.util.stream.Collectors.toList()));
        roles.forEach(role -> assertEquals(Boolean.TRUE, role.get("assignable"), String.valueOf(role.get("roleCode"))));
    }

    private ClubRoleAssignment assignment(Long memberId) {
        ClubRoleAssignment assignment = new ClubRoleAssignment();
        assignment.setRoleCode(ClubRoleCode.CLUB_CO_OWNER);
        assignment.setTargetMemberId(memberId);
        return assignment;
    }

    private static class TestableController extends ApiClubRoleController {
        private final Long memberId;
        private TestableController(Long memberId) { this.memberId = memberId; }
        @Override public Long getAppUserId() { return memberId; }
    }
}
