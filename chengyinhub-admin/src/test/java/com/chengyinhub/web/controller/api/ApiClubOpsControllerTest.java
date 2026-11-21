package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.clubops.ClubOpsService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** club-ops 控制器:登录与可见性口径(未登录 = 没有 SecurityContext,getAppUserId 抛错)。 */
class ApiClubOpsControllerTest {
    private final ClubOpsService ops = Mockito.mock(ClubOpsService.class);
    private final IClubService clubs = Mockito.mock(IClubService.class);
    private final ApiClubOpsController controller = new ApiClubOpsController(ops, Mockito.mock(IClubAccessService.class),
            clubs, Mockito.mock(IClubMemberService.class));

    @Test
    void operatorReadsNeedSignIn() {
        AppGapException projects = assertThrows(AppGapException.class, () -> controller.projects(Map.of("clubId", 1)));
        assertEquals(401, projects.getStatus());
        AppGapException dashboard = assertThrows(AppGapException.class, () -> controller.dashboard(Map.of("clubId", 1)));
        assertEquals(401, dashboard.getStatus());
        Mockito.verifyNoInteractions(ops);
    }

    @Test
    void sortsOfAPrivateClubStayClosedToGuests() {
        Club club = new Club();
        club.setId(5L); club.setMemberId(9L); club.setPublicVisible(0);
        Mockito.when(clubs.selectClubById(5L)).thenReturn(club);
        AppGapException closed = assertThrows(AppGapException.class, () -> controller.leaderboardSorts(Map.of("id", 5)));
        assertEquals(403, closed.getStatus());
        Mockito.verifyNoInteractions(ops);
    }

    @Test
    void sortsOfAPublicClubAreOpen() {
        Club club = new Club();
        club.setId(6L); club.setMemberId(9L); club.setPublicVisible(1);
        Mockito.when(clubs.selectClubById(6L)).thenReturn(club);
        Mockito.when(ops.leaderboardSorts(6L)).thenReturn(Map.of("sorts", java.util.List.of("events")));
        AjaxResult result = controller.leaderboardSorts(Map.of("id", 6));
        assertEquals(200, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void onlyNearbyAndSortsAreAnonymous() throws Exception {
        assertNotNull(ApiClubOpsController.class.getMethod("nearby", Map.class).getAnnotation(Anonymous.class));
        assertNotNull(ApiClubOpsController.class.getMethod("leaderboardSorts", Map.class).getAnnotation(Anonymous.class));
        assertNull(ApiClubOpsController.class.getMethod("projects", Map.class).getAnnotation(Anonymous.class));
        assertNull(ApiClubOpsController.class.getMethod("dashboard", Map.class).getAnnotation(Anonymous.class));
    }
}
